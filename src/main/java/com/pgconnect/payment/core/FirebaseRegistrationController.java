package com.pgconnect.payment.core;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/firebase")
@ConditionalOnProperty(name="pgconnect.firebase.enabled", havingValue="true")
@SecurityRequirement(name="bearerAuth")
class FirebaseRegistrationController {
    record Registration(@NotBlank @Size(max=150) String fullName) {}
    private final FirebaseIdentity firebase;
    private final JdbcTemplate jdbc;
    FirebaseRegistrationController(FirebaseIdentity firebase, JdbcTemplate jdbc) {
        this.firebase=firebase; this.jdbc=jdbc;
    }

    @PostMapping("/register")
    @Transactional
    Map<String,Object> register(@RequestHeader(value="Authorization",required=false) String header,
                                @Valid @RequestBody Registration input) {
        if (header==null || !header.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        FirebaseIdentity.Identity identity=firebase.verify(header.substring(7));
        jdbc.update("""
                INSERT INTO users(full_name,phone_number,firebase_uid) VALUES (?,?,?)
                ON CONFLICT DO NOTHING
                """,input.fullName().trim(),identity.phone(),identity.uid());
        List<UUID> users=jdbc.queryForList("SELECT id FROM users WHERE firebase_uid=? AND phone_number=?",
                UUID.class,identity.uid(),identity.phone());
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Account requires manual review");
        return Map.of("userId",users.get(0));
    }
}
