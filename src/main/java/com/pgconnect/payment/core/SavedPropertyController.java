package com.pgconnect.payment.core;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customer/saved-properties")
@SecurityRequirement(name="bearerAuth")
public class SavedPropertyController {
    private final JdbcTemplate jdbc;
    public SavedPropertyController(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @GetMapping
    public List<Map<String,Object>> list(@AuthenticationPrincipal UUID user) {
        return jdbc.queryForList("""
                SELECT p.id,p.name,p.area,p.city,
                  (SELECT ph.id FROM property_photos ph WHERE ph.property_id=p.id AND ph.position=0) AS "coverPhotoId"
                FROM saved_properties s JOIN properties p ON p.id=s.property_id
                WHERE s.user_id=? AND p.active ORDER BY s.created_at DESC,p.id
                """,user);
    }
    @PostMapping("/{id}")
    public void save(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        int changed=jdbc.update("""
                INSERT INTO saved_properties(user_id,property_id)
                SELECT ?,id FROM properties WHERE id=? AND active
                ON CONFLICT (user_id,property_id) DO UPDATE SET property_id=EXCLUDED.property_id
                """,user,id);
        Access.found(changed);
    }
    @DeleteMapping("/{id}")
    public void remove(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        jdbc.update("DELETE FROM saved_properties WHERE user_id=? AND property_id=?",user,id);
    }
}
