package com.pgconnect.payment.core;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PropertyPhotoController {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000L;
    private static final int MAX_PHOTOS = 10;
    private final JdbcTemplate jdbc;
    private final Access access;

    public PropertyPhotoController(JdbcTemplate jdbc, Access access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record Photo(UUID id, int position, boolean cover) {}
    public record PhotoOrder(@NotNull @Size(max=MAX_PHOTOS) List<@NotNull UUID> photoIds) {}
    private record PhotoData(String contentType, byte[] content) {}

    private void owned(UUID propertyId, UUID user, boolean lock) {
        UUID owner = access.owner(user);
        String sql = "SELECT id FROM properties WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : "");
        if (jdbc.query(sql, (rs, row) -> rs.getObject(1, UUID.class), propertyId, owner).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Property not found");
        }
    }

    private void active(UUID propertyId) {
        if (jdbc.query("SELECT id FROM properties WHERE id=? AND active", (rs, row) -> rs.getObject(1, UUID.class), propertyId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Property not found");
        }
    }

    private List<Photo> photos(UUID propertyId) {
        return jdbc.query("SELECT id,position FROM property_photos WHERE property_id=? ORDER BY position",
            (rs, row) -> new Photo(rs.getObject("id", UUID.class), rs.getInt("position"), rs.getInt("position") == 0), propertyId);
    }

    @GetMapping("/properties/{id}/photos")
    public List<Photo> publicPhotos(@PathVariable UUID id) {
        active(id);
        return photos(id);
    }

    @GetMapping("/owner/properties/{id}/photos")
    @SecurityRequirement(name="bearerAuth")
    public List<Photo> ownerPhotos(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        owned(id, user, false);
        return photos(id);
    }

    @GetMapping("/properties/{id}/photos/{photoId}")
    public ResponseEntity<byte[]> publicPhoto(@PathVariable UUID id, @PathVariable UUID photoId) {
        active(id);
        return photo(id, photoId);
    }

    @GetMapping("/owner/properties/{id}/photos/{photoId}")
    @SecurityRequirement(name="bearerAuth")
    public ResponseEntity<byte[]> ownerPhoto(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @PathVariable UUID photoId) {
        owned(id, user, false);
        return photo(id, photoId);
    }

    private ResponseEntity<byte[]> photo(UUID id, UUID photoId) {
        List<PhotoData> rows = jdbc.query("SELECT content_type,content FROM property_photos WHERE id=? AND property_id=?",
            (rs, row) -> new PhotoData(rs.getString("content_type"), rs.getBytes("content")), photoId, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found");
        PhotoData photo = rows.get(0);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType()))
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(photo.content());
    }

    @PostMapping(value="/owner/properties/{id}/photos", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @SecurityRequirement(name="bearerAuth")
    @Transactional
    public Photo upload(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        owned(id, user, true);
        List<Photo> existing = photos(id);
        if (existing.size() >= MAX_PHOTOS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maximum 10 photos per property");
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Photo must be between 1 byte and 5 MB");
        byte[] content = file.getBytes();
        String type = validateImage(content);
        UUID photoId = UUID.randomUUID();
        jdbc.update("INSERT INTO property_photos(id,property_id,position,content_type,content) VALUES (?,?,?,?,?)",
            photoId, id, existing.size(), type, content);
        return new Photo(photoId, existing.size(), existing.isEmpty());
    }

    private String validateImage(byte[] content) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only JPEG and PNG images are supported");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png"))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only JPEG and PNG images are supported");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image dimensions exceed 16 megapixels");
                if (reader.read(0) == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image");
                return format.equals("jpeg") ? "image/jpeg" : "image/png";
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or damaged image", e);
        }
    }

    @PutMapping("/owner/properties/{id}/photos/order")
    @SecurityRequirement(name="bearerAuth")
    @Transactional
    public List<Photo> reorder(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @Valid @RequestBody PhotoOrder body) {
        owned(id, user, true);
        List<UUID> current = photos(id).stream().map(Photo::id).toList();
        List<UUID> requested = body.photoIds();
        if (requested.size() != current.size() || new HashSet<>(requested).size() != requested.size()
            || !new HashSet<>(current).equals(new HashSet<>(requested))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order must include each property photo exactly once");
        }
        updatePositions(id, requested);
        return photos(id);
    }

    @PutMapping("/owner/properties/{id}/photos/{photoId}/cover")
    @SecurityRequirement(name="bearerAuth")
    @Transactional
    public List<Photo> cover(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @PathVariable UUID photoId) {
        owned(id, user, true);
        List<UUID> ids = new ArrayList<>(photos(id).stream().map(Photo::id).toList());
        if (!ids.remove(photoId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found");
        ids.add(0, photoId);
        updatePositions(id, ids);
        return photos(id);
    }

    @DeleteMapping("/owner/properties/{id}/photos/{photoId}")
    @SecurityRequirement(name="bearerAuth")
    @Transactional
    public void delete(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @PathVariable UUID photoId) {
        owned(id, user, true);
        Access.found(jdbc.update("DELETE FROM property_photos WHERE id=? AND property_id=?", photoId, id));
        updatePositions(id, photos(id).stream().map(Photo::id).toList());
    }

    private void updatePositions(UUID propertyId, List<UUID> ids) {
        for (int i = 0; i < ids.size(); i++)
            jdbc.update("UPDATE property_photos SET position=? WHERE property_id=? AND id=?", i, propertyId, ids.get(i));
    }
}
