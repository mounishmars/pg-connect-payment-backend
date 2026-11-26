package com.pgconnect.payment.core;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
public class PropertyController {
    private final JdbcTemplate jdbc;
    private final Access access;
    public PropertyController(JdbcTemplate jdbc, Access access) { this.jdbc=jdbc; this.access=access; }
    public record PropertyInput(@NotBlank @Size(max=150) String name, @NotNull @Size(max=5000) String description,
            @NotBlank @Size(max=1000) String address, @NotBlank @Size(max=150) String area,
            @NotBlank @Size(max=150) String city, boolean active) {}
    public record RoomInput(@NotBlank @Size(max=100) String name,
            @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal monthlyRent) {}
    public record BedInput(@NotBlank @Size(max=100) String label) {}
    public record RoomUpdate(@NotBlank @Size(max=100) String name,
            @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal monthlyRent, boolean active) {}
    public record BedUpdate(@NotBlank @Size(max=100) String label, boolean active) {}
    public record AmenitySelection(@NotNull @Size(max=20) List<@NotBlank @Size(max=30) String> codes) {}

    @GetMapping("/properties")
    public List<Map<String,Object>> search(@RequestParam(defaultValue="") @Size(max=150) String query,
                                           @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,
                                           @RequestParam(required=false) @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal maxRent,
                                           @RequestParam(defaultValue="false") boolean availableOnly,
                                           @RequestParam(required=false) List<@NotBlank @Size(max=30) String> amenity) {
        if (amenity != null && amenity.size() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many amenities");
        StringBuilder sql = new StringBuilder("""
                SELECT p.id,p.name,p.area,p.city,
                  (SELECT ph.id FROM property_photos ph WHERE ph.property_id=p.id AND ph.position=0) AS "coverPhotoId",
                  (SELECT MIN(r.monthly_rent) FROM rooms r WHERE r.property_id=p.id AND r.active) AS "startingRent",
                  (SELECT COUNT(*) FROM rooms r JOIN beds b ON b.room_id=r.id
                   WHERE r.property_id=p.id AND r.active AND b.active AND NOT EXISTS
                     (SELECT 1 FROM bookings bk WHERE bk.bed_id=b.id AND bk.status='APPROVED')) AS "availableBeds"
                FROM properties p WHERE p.active AND (p.name ILIKE ? OR p.city ILIKE ? OR p.area ILIKE ?)
                """);
        List<Object> params = new ArrayList<>(List.of("%"+query+"%","%"+query+"%","%"+query+"%"));
        if (maxRent != null || availableOnly) {
            sql.append(" AND EXISTS (SELECT 1 FROM rooms r WHERE r.property_id=p.id AND r.active");
            if (maxRent != null) { sql.append(" AND r.monthly_rent<=?"); params.add(maxRent); }
            if (availableOnly) sql.append("""
                 AND EXISTS (SELECT 1 FROM beds b WHERE b.room_id=r.id AND b.active AND NOT EXISTS
                   (SELECT 1 FROM bookings bk WHERE bk.bed_id=b.id AND bk.status='APPROVED'))
                """);
            sql.append(")");
        }
        if (amenity != null) for (String code : amenity) {
            sql.append(" AND EXISTS (SELECT 1 FROM property_amenities pa WHERE pa.property_id=p.id AND pa.amenity_code=?)");
            params.add(code);
        }
        sql.append(" ORDER BY p.created_at DESC,p.id LIMIT 50 OFFSET ?");
        params.add(offset);
        return jdbc.queryForList(sql.toString(),params.toArray());
    }
    @GetMapping("/properties/{id}")
    public Map<String,Object> detail(@PathVariable UUID id) {
        List<Map<String,Object>> properties=jdbc.queryForList(
                "SELECT id,name,description,address,area,city FROM properties WHERE id=? AND active",id);
        if (properties.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Property not found");
        Map<String,Object> property=properties.get(0);
        property.put("rooms",jdbc.queryForList("""
                SELECT r.id,r.name,r.monthly_rent AS "monthlyRent",
                  (SELECT COUNT(*) FROM beds b WHERE b.room_id=r.id AND b.active AND NOT EXISTS
                    (SELECT 1 FROM bookings bk WHERE bk.bed_id=b.id AND bk.status='APPROVED')) AS "availableBeds"
                FROM rooms r WHERE r.property_id=? AND r.active ORDER BY r.name
                """,id));
        property.put("availableBeds",jdbc.queryForList("""
                SELECT b.id,b.room_id AS "roomId",b.label FROM beds b JOIN rooms r ON r.id=b.room_id
                WHERE r.property_id=? AND r.active AND b.active AND NOT EXISTS
                  (SELECT 1 FROM bookings bk WHERE bk.bed_id=b.id AND bk.status='APPROVED')
                ORDER BY r.name,b.label
                """,id));
        property.put("photos",jdbc.queryForList("""
                SELECT id,position,(position=0) AS cover FROM property_photos WHERE property_id=? ORDER BY position
                """,id));
        property.put("amenities",jdbc.queryForList("""
                SELECT a.code,a.name FROM amenities a JOIN property_amenities pa ON pa.amenity_code=a.code
                WHERE pa.property_id=? ORDER BY a.name
                """,id));
        return property;
    }
    @GetMapping("/amenities")
    public List<Map<String,Object>> amenities() {
        return jdbc.queryForList("SELECT code,name FROM amenities ORDER BY name");
    }

    @GetMapping("/owner/properties/{id}/amenities")
    @SecurityRequirement(name="bearerAuth")
    public List<String> ownerAmenities(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        ensureOwner(id,user,false);
        return jdbc.queryForList("SELECT amenity_code FROM property_amenities WHERE property_id=? ORDER BY amenity_code",String.class,id);
    }

    @PutMapping("/owner/properties/{id}/amenities")
    @SecurityRequirement(name="bearerAuth")
    @Transactional
    public void setAmenities(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @Valid @RequestBody AmenitySelection body) {
        ensureOwner(id,user,true);
        if (new HashSet<>(body.codes()).size()!=body.codes().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Duplicate amenities");
        for (String code: body.codes()) {
            if (jdbc.queryForList("SELECT code FROM amenities WHERE code=?",String.class,code).isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown amenity");
        }
        jdbc.update("DELETE FROM property_amenities WHERE property_id=?",id);
        for (String code: body.codes()) jdbc.update("INSERT INTO property_amenities(property_id,amenity_code) VALUES (?,?)",id,code);
    }

    private void ensureOwner(UUID id, UUID user, boolean lock) {
        String sql="SELECT id FROM properties WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : "");
        if (jdbc.queryForList(sql,UUID.class,id,access.owner(user)).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Property not found");
    }
    @GetMapping("/owner/properties")
    @SecurityRequirement(name="bearerAuth")
    public List<Map<String,Object>> ownerProperties(@AuthenticationPrincipal UUID user) {
        return jdbc.queryForList("""
                SELECT p.id,p.name,p.description,p.address,p.area,p.city,p.active,
                  (SELECT ph.id FROM property_photos ph WHERE ph.property_id=p.id AND ph.position=0) AS "coverPhotoId"
                FROM properties p WHERE p.owner_id=? ORDER BY p.created_at DESC LIMIT 100
                """,access.owner(user));
    }
    @PostMapping("/owner/properties")
    @SecurityRequirement(name="bearerAuth")
    public Map<String,Object> create(@AuthenticationPrincipal UUID user,@Valid @RequestBody PropertyInput body) {
        return jdbc.queryForMap("""
                INSERT INTO properties(owner_id,name,description,address,area,city,active) VALUES (?,?,?,?,?,?,?) RETURNING id
                """,access.owner(user),body.name(),body.description(),body.address(),body.area(),body.city(),body.active());
    }
    @PutMapping("/owner/properties/{id}")
    @SecurityRequirement(name="bearerAuth")
    public void update(@AuthenticationPrincipal UUID user,@PathVariable UUID id,@Valid @RequestBody PropertyInput body) {
        Access.found(jdbc.update("""
                UPDATE properties SET name=?,description=?,address=?,area=?,city=?,active=? WHERE id=? AND owner_id=?
                """,body.name(),body.description(),body.address(),body.area(),body.city(),body.active(),id,access.owner(user)));
    }
    @PostMapping("/owner/properties/{id}/rooms")
    @SecurityRequirement(name="bearerAuth")
    public Map<String,Object> addRoom(@AuthenticationPrincipal UUID user,@PathVariable UUID id,@Valid @RequestBody RoomInput body) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
                INSERT INTO rooms(property_id,name,monthly_rent)
                SELECT id,?,? FROM properties WHERE id=? AND owner_id=? RETURNING id
                """,body.name(),body.monthlyRent(),id,access.owner(user));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Property not found");
        return rows.get(0);
    }
    @PostMapping("/owner/rooms/{id}/beds")
    @SecurityRequirement(name="bearerAuth")
    public Map<String,Object> addBed(@AuthenticationPrincipal UUID user,@PathVariable UUID id,@Valid @RequestBody BedInput body) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
                INSERT INTO beds(room_id,label) SELECT r.id,? FROM rooms r JOIN properties p ON p.id=r.property_id
                WHERE r.id=? AND p.owner_id=? RETURNING id
                """,body.label(),id,access.owner(user));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Room not found");
        return rows.get(0);
    }
    @PutMapping("/owner/rooms/{id}")
    @SecurityRequirement(name="bearerAuth")
    public void updateRoom(@AuthenticationPrincipal UUID user,@PathVariable UUID id,@Valid @RequestBody RoomUpdate body) {
        Access.found(jdbc.update("""
                UPDATE rooms r SET name=?,monthly_rent=?,active=? FROM properties p
                WHERE r.id=? AND p.id=r.property_id AND p.owner_id=?
                """,body.name(),body.monthlyRent(),body.active(),id,access.owner(user)));
    }
    @PutMapping("/owner/beds/{id}")
    @SecurityRequirement(name="bearerAuth")
    public void updateBed(@AuthenticationPrincipal UUID user,@PathVariable UUID id,@Valid @RequestBody BedUpdate body) {
        Access.found(jdbc.update("""
                UPDATE beds b SET label=?,active=? FROM rooms r JOIN properties p ON p.id=r.property_id
                WHERE b.id=? AND r.id=b.room_id AND p.owner_id=?
                """,body.label(),body.active(),id,access.owner(user)));
    }
    @GetMapping("/owner/properties/{id}/rooms")
    @SecurityRequirement(name="bearerAuth")
    public List<Map<String,Object>> rooms(@AuthenticationPrincipal UUID user,@PathVariable UUID id) {
        UUID owner=access.owner(user);
        Integer exists=jdbc.queryForObject("SELECT count(*) FROM properties WHERE id=? AND owner_id=?",Integer.class,id,owner);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Property not found");
        return jdbc.queryForList("""
                SELECT r.id,r.name,r.monthly_rent AS "monthlyRent",r.active,
                  b.id AS "bedId",b.label AS "bedLabel",b.active AS "bedActive"
                FROM rooms r LEFT JOIN beds b ON b.room_id=r.id WHERE r.property_id=? ORDER BY r.name,b.label
                """,id);
    }
}
