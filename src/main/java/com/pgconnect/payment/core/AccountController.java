package com.pgconnect.payment.core;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
public class AccountController {
    private final JdbcTemplate jdbc;
    private final Access access;
    public AccountController(JdbcTemplate jdbc, Access access) { this.jdbc=jdbc; this.access=access; }
    public record Application(@NotBlank @Size(max=150) String displayName) {}
    public record Profile(@NotBlank @Size(max=150) String fullName) {}

    @GetMapping("/me")
    public Map<String,Object> me(@AuthenticationPrincipal UUID user) {
        return jdbc.queryForMap("""
                SELECT u.id,u.full_name AS "fullName",u.phone_number AS phone,u.is_admin AS "isAdmin",
                  o.id AS "ownerId",a.status AS "ownerApplicationStatus"
                FROM users u LEFT JOIN owner_profiles o ON o.user_id=u.id
                LEFT JOIN owner_applications a ON a.user_id=u.id WHERE u.id=?
                """, user);
    }
    @PutMapping("/me")
    public void updateProfile(@AuthenticationPrincipal UUID user, @Valid @RequestBody Profile profile) {
        Access.found(jdbc.update("UPDATE users SET full_name=? WHERE id=?", profile.fullName(), user));
    }
    @PostMapping("/auth/logout")
    public void logout(@Parameter(hidden=true) @RequestHeader("Authorization") String header, @AuthenticationPrincipal UUID user) {
        jdbc.update("DELETE FROM auth_sessions WHERE token_hash=? AND user_id=?", Tokens.hash(header.substring(7)), user);
    }
    @PostMapping("/owner-applications")
    public Map<String,Object> apply(@AuthenticationPrincipal UUID user, @Valid @RequestBody Application body) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                INSERT INTO owner_applications(user_id,display_name) VALUES (?,?)
                ON CONFLICT(user_id) DO UPDATE SET display_name=EXCLUDED.display_name,status='PENDING',updated_at=now()
                WHERE owner_applications.status='REJECTED' RETURNING id,status
                """, user, body.displayName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Application already pending or approved");
        return rows.get(0);
    }
    @GetMapping("/admin/owner-applications")
    public List<Map<String,Object>> applications(@AuthenticationPrincipal UUID user) {
        access.admin(user);
        return jdbc.queryForList("""
                SELECT a.id,a.user_id AS "userId",u.full_name AS "fullName",u.phone_number AS phone,
                  a.display_name AS "displayName",a.status,a.created_at AS "createdAt"
                FROM owner_applications a JOIN users u ON u.id=a.user_id
                WHERE a.status='PENDING' ORDER BY a.created_at LIMIT 100
                """);
    }
    @PostMapping("/admin/owner-applications/{id}/approve")
    @Transactional
    public void approve(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        access.admin(user);
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT user_id,display_name FROM owner_applications WHERE id=? AND status='PENDING' FOR UPDATE
                """, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pending application not found");
        Map<String,Object> row=rows.get(0);
        jdbc.update("INSERT INTO owner_profiles(user_id,display_name) VALUES (?,?)",row.get("user_id"),row.get("display_name"));
        jdbc.update("UPDATE owner_applications SET status='APPROVED',updated_at=now() WHERE id=?",id);
    }
    @PostMapping("/admin/owner-applications/{id}/reject")
    public void reject(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        access.admin(user);
        Access.found(jdbc.update("UPDATE owner_applications SET status='REJECTED',updated_at=now() WHERE id=? AND status='PENDING'",id));
    }
}
