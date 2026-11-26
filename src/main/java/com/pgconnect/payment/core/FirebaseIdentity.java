package com.pgconnect.payment.core;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Configuration
@ConditionalOnProperty(name="pgconnect.firebase.enabled", havingValue="true")
class FirebaseConfiguration {
    @Bean
    FirebaseAuth firebaseAuth(@Value("${pgconnect.firebase.project-id:}") String projectId,
                              Environment environment) throws IOException {
        if (environment.acceptsProfiles(Profiles.of("dev")))
            throw new IllegalStateException("Firebase authentication cannot run with the dev profile");
        if (System.getenv("FIREBASE_AUTH_EMULATOR_HOST") != null)
            throw new IllegalStateException("Firebase authentication emulator is not allowed");
        if (projectId.isBlank()) throw new IllegalStateException("Firebase project ID must be configured");
        FirebaseOptions options=FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault()).setProjectId(projectId).build();
        return FirebaseAuth.getInstance(FirebaseApp.initializeApp(options,"pgconnect"));
    }
}

@Service
@ConditionalOnProperty(name="pgconnect.firebase.enabled", havingValue="true")
class FirebaseIdentity {
    private static final Logger log=LoggerFactory.getLogger(FirebaseIdentity.class);
    record Identity(String uid, String phone) {}
    private static final Set<AuthErrorCode> INVALID_TOKENS=Set.of(
            AuthErrorCode.INVALID_ID_TOKEN, AuthErrorCode.EXPIRED_ID_TOKEN, AuthErrorCode.REVOKED_ID_TOKEN,
            AuthErrorCode.USER_DISABLED, AuthErrorCode.USER_NOT_FOUND, AuthErrorCode.TENANT_ID_MISMATCH);
    private final FirebaseAuth auth;
    FirebaseIdentity(FirebaseAuth auth) { this.auth=auth; }

    Identity verify(String token) {
        if (token == null || token.isBlank() || token.length() > 8192)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid authentication token");
        try {
            FirebaseToken decoded=auth.verifyIdToken(token,true);
            Object phone=decoded.getClaims().get("phone_number");
            Object firebase=decoded.getClaims().get("firebase");
            if (!(phone instanceof String number) || !number.matches("\\+[1-9][0-9]{7,14}")
                    || !(firebase instanceof Map<?,?> claims) || !"phone".equals(claims.get("sign_in_provider"))
                    || decoded.getUid().isBlank() || decoded.getUid().length()>128)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Phone authentication required");
            return new Identity(decoded.getUid(),number);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode()!=null && INVALID_TOKENS.contains(e.getAuthErrorCode()))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid or expired authentication",e);
            log.warn("Firebase token verification unavailable: {}", e.getAuthErrorCode());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Authentication service unavailable",e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid authentication token",e);
        }
    }

    void revoke(String uid) {
        try { auth.revokeRefreshTokens(uid); }
        catch (FirebaseAuthException e) {
            log.warn("Firebase session revocation failed: {}", e.getAuthErrorCode());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Unable to revoke sessions",e);
        }
    }
}
