package com.pgconnect.payment.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Profile("dev")
@RestController
@RequestMapping("/api/v1/auth/dev")
public class DevAuthController {
    private final JdbcTemplate jdbc;
    private final ChallengeVerifier verifier;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    public DevAuthController(JdbcTemplate jdbc, ChallengeVerifier verifier) { this.jdbc=jdbc; this.verifier=verifier; }
    public record RequestOtp(@NotBlank @Pattern(regexp="\\+[1-9][0-9]{7,14}") String phone) {}
    public record VerifyOtp(@NotBlank @Pattern(regexp="\\+[1-9][0-9]{7,14}") String phone,
                            @NotBlank @Pattern(regexp="[0-9]{6}") String code,
                            @NotBlank @Size(max=150) String fullName) {}

    @PostMapping("/otp")
    public Map<String, String> request(@Valid @RequestBody RequestOtp request) {
        String code = Tokens.otp();
        int changed = jdbc.update("""
                INSERT INTO dev_otp_challenges(phone_number,code_hash,expires_at)
                VALUES (?,?,now()+interval '5 minutes')
                ON CONFLICT(phone_number) DO UPDATE SET code_hash=EXCLUDED.code_hash, expires_at=EXCLUDED.expires_at,
                  attempts=0, requested_at=now()
                WHERE dev_otp_challenges.requested_at < now()-interval '30 seconds'
                """, request.phone(), encoder.encode(code));
        if (changed == 0) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Wait before requesting another code");
        return Map.of("code", code, "warning", "Development only; no SMS was sent");
    }
    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody VerifyOtp request) {
        Map<String, Object> result = verifier.verify(request);
        if (result == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired code");
        return result;
    }
}

@Profile("dev")
@Service
class ChallengeVerifier {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    ChallengeVerifier(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional
    public Map<String, Object> verify(DevAuthController.VerifyOtp request) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT code_hash FROM dev_otp_challenges WHERE phone_number=? AND expires_at>now() AND attempts<5 FOR UPDATE
                """, request.phone());
        if (rows.isEmpty()) return null;
        if (!encoder.matches(request.code(), (String) rows.get(0).get("code_hash"))) {
            jdbc.update("UPDATE dev_otp_challenges SET attempts=attempts+1 WHERE phone_number=?", request.phone());
            return null;
        }
        jdbc.update("DELETE FROM dev_otp_challenges WHERE phone_number=?", request.phone());
        UUID user = jdbc.queryForObject("""
                INSERT INTO users(full_name,phone_number) VALUES (?,?)
                ON CONFLICT(phone_number) DO UPDATE SET phone_number=EXCLUDED.phone_number RETURNING id
                """, UUID.class, request.fullName(), request.phone());
        String token = Tokens.random();
        jdbc.update("INSERT INTO auth_sessions(token_hash,user_id,expires_at) VALUES (?,?,now()+interval '24 hours')", Tokens.hash(token), user);
        return Map.of("accessToken",token,"tokenType","Bearer","expiresInSeconds",86400,"userId",user);
    }
}
