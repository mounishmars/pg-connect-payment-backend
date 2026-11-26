package com.pgconnect.payment.core;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
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

    @GetMapping("/properties")
    public List<Map<String,Object>> search(@RequestParam(defaultValue="") @Size(max=150) String query,
                                           @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset) {
        return jdbc.queryForList("""
                SELECT p.id,p.name,p.area,p.city,
                  (SELECT MIN(r.monthly_rent) FROM rooms r WHERE r.property_id=p.id AND r.active) AS "startingRent",
                  (SELECT COUNT(*) FROM rooms r JOIN beds b ON b.room_id=r.id
                   WHERE r.property_id=p.id AND r.active AND b.active AND NOT EXISTS
                     (SELECT 1 FROM bookings bk WHERE bk.bed_id=b.id AND bk.status='APPROVED')) AS "availableBeds"
                FROM properties p WHERE p.active AND (p.name ILIKE ? OR p.city ILIKE ? OR p.area ILIKE ?)
                ORDER BY p.created_at DESC,p.id LIMIT 50 OFFSET ?
                ""","%"+query+"%","%"+query+"%","%"+query+"%",offset);
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
        return property;
    }
    @GetMapping("/owner/properties")
    @SecurityRequirement(name="bearerAuth")
    public List<Map<String,Object>> ownerProperties(@AuthenticationPrincipal UUID user) {
        return jdbc.queryForList("SELECT id,name,description,address,area,city,active FROM properties WHERE owner_id=? ORDER BY created_at DESC LIMIT 100",access.owner(user));
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
