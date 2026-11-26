package com.pgconnect.payment.core;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="pgconnect.firebase.enabled=true")
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="PGCONNECT_INTEGRATION_TESTS",matches="true")
class FirebaseAuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean FirebaseAuth auth;
    @MockBean FirebaseIdentity firebase;

    @Test
    void registersVerifiedIdentityAndRevokesSessionsOnLogout() throws Exception {
        when(firebase.verify("valid")).thenReturn(new FirebaseIdentity.Identity("firebase-uid","+919000011111"));
        var register=post("/api/v1/auth/firebase/register").header("Authorization","Bearer valid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"User\"}");
        mvc.perform(register).andExpect(status().isOk());
        mvc.perform(register).andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM users WHERE firebase_uid='firebase-uid'",Integer.class));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer valid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("User"));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer valid")).andExpect(status().isOk());
        verify(firebase).revoke("firebase-uid");
    }

    @Test
    void existingPhoneIsNeverLinkedAndDevTokensAreRejected() throws Exception {
        UUID user=jdbc.queryForObject("INSERT INTO users(full_name,phone_number,is_admin) VALUES ('Old','+919000022222',true) RETURNING id",UUID.class);
        when(firebase.verify("new")).thenReturn(new FirebaseIdentity.Identity("new-uid","+919000022222"));
        mvc.perform(post("/api/v1/auth/firebase/register").header("Authorization","Bearer new")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"New\"}"))
                .andExpect(status().isConflict());
        assertNull(jdbc.queryForObject("SELECT firebase_uid FROM users WHERE id=?",String.class,user));
        when(firebase.verify("dev-token")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid authentication"));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer dev-token")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/dev/otp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidAndUnavailableCredentialsFailClosed() throws Exception {
        when(firebase.verify("revoked")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid authentication"));
        when(firebase.verify("outage")).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Authentication unavailable"));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer revoked")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer outage")).andExpect(status().isServiceUnavailable());
    }
}
