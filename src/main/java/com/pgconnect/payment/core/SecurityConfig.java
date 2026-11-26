package com.pgconnect.payment.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JdbcTemplate jdbc,
                                            ObjectProvider<FirebaseIdentity> firebase, Environment environment) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/dev/otp", "/api/v1/auth/dev/verify",
                                "/api/v1/auth/firebase/register", "/api/v1/webhooks/stripe").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info", "/api/v1/properties", "/api/v1/properties/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/properties/*/photos", "/api/v1/properties/*/photos/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/amenities").permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, ex) -> response.sendError(401)))
                .addFilterBefore(new TokenFilter(jdbc, firebase, environment.acceptsProfiles(Profiles.of("dev"))),
                        UsernamePasswordAuthenticationFilter.class).build();
    }

    static final class TokenFilter extends OncePerRequestFilter {
        private final JdbcTemplate jdbc;
        private final ObjectProvider<FirebaseIdentity> firebase;
        private final boolean development;
        TokenFilter(JdbcTemplate jdbc, ObjectProvider<FirebaseIdentity> firebase, boolean development) {
            this.jdbc=jdbc; this.firebase=firebase; this.development=development;
        }
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")
                    && !request.getRequestURI().equals("/api/v1/auth/firebase/register")) {
                String token = header.substring(7);
                FirebaseIdentity identity=firebase.getIfAvailable();
                if (identity != null) {
                    try {
                        FirebaseIdentity.Identity verified=identity.verify(token);
                        List<UUID> users=jdbc.queryForList(
                                "SELECT id FROM users WHERE firebase_uid=? AND phone_number=?",UUID.class,verified.uid(),verified.phone());
                        if (!users.isEmpty()) SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(users.get(0), null, List.of()));
                    } catch (ResponseStatusException e) {
                        response.sendError(e.getStatusCode().value(),e.getReason());
                        return;
                    }
                } else if (development && !token.isBlank() && token.length() <= 256) {
                    List<UUID> users = jdbc.query("SELECT user_id FROM auth_sessions WHERE token_hash=? AND expires_at>now()",
                            (rs, row) -> rs.getObject(1, UUID.class), Tokens.hash(token));
                    if (!users.isEmpty()) SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(users.get(0), null, List.of()));
                }
            }
            chain.doFilter(request, response);
        }
    }
}

final class Tokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Tokens() {}
    static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String otp() { return String.format("%06d", RANDOM.nextInt(1_000_000)); }
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
