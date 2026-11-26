package com.pgconnect.payment.core;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@Component
public class Access {
    private final JdbcTemplate jdbc;
    public Access(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public UUID owner(UUID user) {
        List<UUID> ids = jdbc.query("SELECT id FROM owner_profiles WHERE user_id=?", (rs,row)->rs.getObject(1,UUID.class), user);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approved owner access required");
        return ids.get(0);
    }
    public void admin(UUID user) {
        Boolean admin = jdbc.queryForObject("SELECT is_admin FROM users WHERE id=?", Boolean.class, user);
        if (!Boolean.TRUE.equals(admin)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
    }
    public static void found(int changed) {
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
    }
}
