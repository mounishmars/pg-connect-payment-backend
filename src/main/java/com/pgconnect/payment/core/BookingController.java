package com.pgconnect.payment.core;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name="bearerAuth")
public class BookingController {
    private final JdbcTemplate jdbc;
    private final Access access;
    public BookingController(JdbcTemplate jdbc,Access access) { this.jdbc=jdbc; this.access=access; }
    public record BookingRequest(@NotNull UUID bedId) {}
    @PostMapping("/customer/bookings")
    @Transactional
    public Map<String,Object> request(@AuthenticationPrincipal UUID user,@Valid @RequestBody BookingRequest body) {
        List<UUID> beds=jdbc.query("""
                SELECT b.id FROM beds b JOIN rooms r ON r.id=b.room_id JOIN properties p ON p.id=r.property_id
                WHERE b.id=? AND b.active AND r.active AND p.active FOR UPDATE OF b
                """,(rs,row)->rs.getObject(1,UUID.class),body.bedId());
        if (beds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Available bed not found");
        Integer occupied=jdbc.queryForObject("SELECT count(*) FROM bookings WHERE bed_id=? AND status='APPROVED'",Integer.class,body.bedId());
        if (occupied != null && occupied > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Bed is occupied");
        return jdbc.queryForMap("INSERT INTO bookings(tenant_user_id,bed_id) VALUES (?,?) RETURNING id,status",user,body.bedId());
    }
    @GetMapping("/customer/bookings")
    public List<Map<String,Object>> customer(@AuthenticationPrincipal UUID user) {
        return jdbc.queryForList("""
                SELECT bk.id,bk.status,bk.created_at AS "createdAt",p.id AS "propertyId",p.name AS "propertyName",
                  r.name AS "roomName",b.label AS "bedLabel"
                FROM bookings bk JOIN beds b ON b.id=bk.bed_id JOIN rooms r ON r.id=b.room_id
                JOIN properties p ON p.id=r.property_id WHERE bk.tenant_user_id=? ORDER BY bk.created_at DESC LIMIT 100
                """,user);
    }
    @PostMapping("/customer/bookings/{id}/cancel")
    public void cancel(@AuthenticationPrincipal UUID user,@PathVariable UUID id) {
        Access.found(jdbc.update("UPDATE bookings SET status='CANCELLED',updated_at=now() WHERE id=? AND tenant_user_id=? AND status='PENDING'",id,user));
    }
    @GetMapping("/owner/bookings")
    public List<Map<String,Object>> owner(@AuthenticationPrincipal UUID user) {
        return jdbc.queryForList("""
                SELECT bk.id,bk.status,bk.created_at AS "createdAt",u.id AS "tenantId",
                  u.full_name AS "tenantName",u.phone_number AS "tenantPhone",
                  p.id AS "propertyId",p.name AS "propertyName",r.name AS "roomName",b.label AS "bedLabel"
                FROM bookings bk JOIN users u ON u.id=bk.tenant_user_id JOIN beds b ON b.id=bk.bed_id
                JOIN rooms r ON r.id=b.room_id JOIN properties p ON p.id=r.property_id
                WHERE p.owner_id=? ORDER BY bk.created_at DESC LIMIT 100
                """,access.owner(user));
    }
    @PostMapping("/owner/bookings/{id}/approve")
    @Transactional
    public void approve(@AuthenticationPrincipal UUID user,@PathVariable UUID id) {
        List<UUID> beds=jdbc.query("""
                SELECT b.id FROM bookings bk JOIN beds b ON b.id=bk.bed_id JOIN rooms r ON r.id=b.room_id
                JOIN properties p ON p.id=r.property_id
                WHERE bk.id=? AND p.owner_id=? AND b.active AND r.active AND p.active FOR UPDATE OF b
                """,(rs,row)->rs.getObject(1,UUID.class),id,access.owner(user));
        if (beds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Booking or active bed not found");
        Integer occupied=jdbc.queryForObject("SELECT count(*) FROM bookings WHERE bed_id=? AND status='APPROVED'",Integer.class,beds.get(0));
        if (occupied != null && occupied > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Bed is occupied");
        Access.found(jdbc.update("UPDATE bookings SET status='APPROVED',updated_at=now() WHERE id=? AND status='PENDING'",id));
        jdbc.update("UPDATE bookings SET status='REJECTED',updated_at=now() WHERE bed_id=? AND status='PENDING'",beds.get(0));
    }
    @PostMapping("/owner/bookings/{id}/reject")
    public void reject(@AuthenticationPrincipal UUID user,@PathVariable UUID id) { transition(user,id,"PENDING","REJECTED"); }
    @PostMapping("/owner/bookings/{id}/complete")
    public void complete(@AuthenticationPrincipal UUID user,@PathVariable UUID id) { transition(user,id,"APPROVED","COMPLETED"); }
    private void transition(UUID user,UUID id,String from,String to) {
        Access.found(jdbc.update("""
                UPDATE bookings bk SET status=?,updated_at=now() FROM beds b JOIN rooms r ON r.id=b.room_id
                JOIN properties p ON p.id=r.property_id WHERE bk.id=? AND bk.bed_id=b.id AND p.owner_id=? AND bk.status=?
                """,to,id,access.owner(user),from));
    }
}
