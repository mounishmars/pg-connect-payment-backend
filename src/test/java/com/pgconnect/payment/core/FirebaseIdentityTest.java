package com.pgconnect.payment.core;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirebaseIdentityTest {
    private final FirebaseAuth auth=mock(FirebaseAuth.class);
    private final FirebaseIdentity identity=new FirebaseIdentity(auth);

    @Test
    void acceptsOnlyVerifiedPhoneIdentityAndChecksRevocation() throws Exception {
        FirebaseToken token=mock(FirebaseToken.class);
        when(auth.verifyIdToken("valid",true)).thenReturn(token);
        when(token.getUid()).thenReturn("uid-1");
        when(token.getClaims()).thenReturn(Map.of(
                "phone_number","+919000000001","firebase",Map.of("sign_in_provider","phone")));
        assertEquals(new FirebaseIdentity.Identity("uid-1","+919000000001"),identity.verify("valid"));
        verify(auth).verifyIdToken("valid",true);
        when(token.getClaims()).thenReturn(Map.of(
                "phone_number","+919000000001","firebase",Map.of("sign_in_provider","password")));
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class,()->identity.verify("valid")).getStatusCode());
    }

    @Test
    void distinguishesRevokedTokensFromProviderOutages() throws Exception {
        FirebaseAuthException revoked=mock(FirebaseAuthException.class);
        when(revoked.getAuthErrorCode()).thenReturn(AuthErrorCode.REVOKED_ID_TOKEN);
        when(auth.verifyIdToken("revoked",true)).thenThrow(revoked);
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class,()->identity.verify("revoked")).getStatusCode());
        FirebaseAuthException outage=mock(FirebaseAuthException.class);
        when(auth.verifyIdToken("outage",true)).thenThrow(outage);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class,()->identity.verify("outage")).getStatusCode());
    }
}
