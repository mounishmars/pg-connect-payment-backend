package com.pgconnect.payment.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
@EnabledIfEnvironmentVariable(named="PGCONNECT_INTEGRATION_TESTS",matches="true")
class CoreApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private JsonNode postJson(String path,String token,Object body,int status) throws Exception {
        var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        if (token!=null) request.header("Authorization","Bearer "+token);
        String response=mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return response.isBlank() ? json.nullNode() : json.readTree(response);
    }
    private JsonNode signIn(String phone) throws Exception {
        String code=postJson("/api/v1/auth/dev/otp",null,Map.of("phone",phone),200).get("code").asText();
        return postJson("/api/v1/auth/dev/verify",null,Map.of("phone",phone,"code",code,"fullName","Test User"),200);
    }
    @Test
    void swaggerDescribesPublicAndAuthenticatedEndpoints() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        String response=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode docs=json.readTree(response);
        assertEquals("http",docs.at("/components/securitySchemes/bearerAuth/type").asText());
        assertEquals("bearer",docs.at("/components/securitySchemes/bearerAuth/scheme").asText());
        assertTrue(docs.at("/paths/~1api~1v1~1auth~1dev~1otp/post").isObject());
        assertTrue(docs.at("/paths/~1api~1v1~1properties/get/security").isMissingNode());
        assertTrue(docs.at("/paths/~1api~1v1~1owner~1properties/get/security/0/bearerAuth").isArray());
        assertTrue(docs.at("/paths/~1api~1v1~1customer~1rent~1payments/post/security/0/bearerAuth").isArray());
        assertTrue(docs.at("/paths/~1api~1v1~1auth~1logout/post/parameters").isMissingNode());
        mvc.perform(get("/api/v1/customer/invoices")).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerApprovalBookingAndInvoicesAreScopedToTheirUsers() throws Exception {
        JsonNode admin=signIn("+919000000001"), owner=signIn("+919000000002"),
                tenant=signIn("+919000000003"), other=signIn("+919000000004");
        String adminToken=admin.get("accessToken").asText(),ownerToken=owner.get("accessToken").asText(),
                tenantToken=tenant.get("accessToken").asText(),otherToken=other.get("accessToken").asText();
        jdbc.update("UPDATE users SET is_admin=true WHERE id=?",UUID.fromString(admin.get("userId").asText()));
        JsonNode application=postJson("/api/v1/owner-applications",ownerToken,Map.of("displayName","My PG"),200);
        mvc.perform(get("/api/v1/owner/properties").header("Authorization","Bearer "+ownerToken)).andExpect(status().isForbidden());
        String approve="/api/v1/admin/owner-applications/"+application.get("id").asText()+"/approve";
        postJson(approve,otherToken,Map.of(),403);
        postJson(approve,adminToken,Map.of(),200);
        JsonNode property=postJson("/api/v1/owner/properties",ownerToken,
                Map.of("name","Test PG","description","","address","Address","area","OMR","city","Chennai","active",true),200);
        String propertyId=property.get("id").asText();
        JsonNode room=postJson("/api/v1/owner/properties/"+propertyId+"/rooms",ownerToken,Map.of("name","101","monthlyRent","8000.50"),200);
        JsonNode bed=postJson("/api/v1/owner/rooms/"+room.get("id").asText()+"/beds",ownerToken,Map.of("label","A"),200);
        String bedId=bed.get("id").asText();
        mvc.perform(put("/api/v1/owner/beds/"+bedId).header("Authorization","Bearer "+otherToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"B\",\"active\":true}"))
                .andExpect(status().isForbidden());
        JsonNode first=postJson("/api/v1/customer/bookings",tenantToken,Map.of("bedId",bedId),200);
        JsonNode second=postJson("/api/v1/customer/bookings",otherToken,Map.of("bedId",bedId),200);
        postJson("/api/v1/owner/bookings/"+first.get("id").asText()+"/approve",ownerToken,Map.of(),200);
        assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,UUID.fromString(second.get("id").asText())));
        postJson("/api/v1/customer/bookings",otherToken,Map.of("bedId",bedId),409);
        JsonNode invoice=postJson("/api/v1/owner/invoices",ownerToken,Map.of("bookingId",first.get("id").asText(),"amount","8000.50","dueDate","2026-12-01"),200);
        mvc.perform(get("/api/v1/customer/rent/payments/active").param("invoiceId",invoice.get("id").asText())
                .header("Authorization","Bearer "+tenantToken)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/customer/rent/payments/active").param("invoiceId",invoice.get("id").asText())
                .header("Authorization","Bearer "+otherToken)).andExpect(status().isForbidden());
        String invoices=mvc.perform(get("/api/v1/customer/invoices").header("Authorization","Bearer "+tenantToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1,json.readTree(invoices).size());
        String otherInvoices=mvc.perform(get("/api/v1/customer/invoices").header("Authorization","Bearer "+otherToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(0,json.readTree(otherInvoices).size());
        mvc.perform(get("/api/v1/owner/dashboard").header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalBeds").value(1))
                .andExpect(jsonPath("$.occupied").value(1)).andExpect(jsonPath("$.vacantBeds").value(0))
                .andExpect(jsonPath("$.rentDue").value(8000.50));
        mvc.perform(put("/api/v1/owner/beds/"+bedId).header("Authorization","Bearer "+ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"A\",\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/owner/dashboard").header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalBeds").value(1))
                .andExpect(jsonPath("$.occupied").value(1)).andExpect(jsonPath("$.vacantBeds").value(0));
        mvc.perform(post("/api/v1/customer/rent/payments").header("X-Authenticated-User",tenant.get("userId").asText())
                .header("Idempotency-Key","key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceId\":\""+UUID.randomUUID()+"\",\"amount\":10,\"customerEmail\":\"a@example.com\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customer/invoices").header("X-Authenticated-User",tenant.get("userId").asText()))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/logout",tenantToken,Map.of(),200);
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+tenantToken)).andExpect(status().isUnauthorized());
    }
    @Test
    void otpIsSingleUseAndRejectsBadCodes() throws Exception {
        String phone="+919000000005";
        String code=postJson("/api/v1/auth/dev/otp",null,Map.of("phone",phone),200).get("code").asText();
        String wrong=code.equals("000000") ? "000001" : "000000";
        postJson("/api/v1/auth/dev/verify",null,Map.of("phone",phone,"code",wrong,"fullName","Test"),401);
        postJson("/api/v1/auth/dev/verify",null,Map.of("phone",phone,"code",code,"fullName","Test"),200);
        postJson("/api/v1/auth/dev/verify",null,Map.of("phone",phone,"code",code,"fullName","Test"),401);
    }

    @Test
    void propertyPhotosAreScopedOrderedAndRestrictedToValidImages() throws Exception {
        JsonNode owner=signIn("+919000000006"), other=signIn("+919000000007");
        String ownerToken=owner.get("accessToken").asText(), otherToken=other.get("accessToken").asText();
        UUID ownerId=UUID.randomUUID(), otherId=UUID.randomUUID();
        jdbc.update("INSERT INTO owner_profiles(id,user_id,display_name) VALUES (?,?,?),(?,?,?)",
                ownerId,UUID.fromString(owner.get("userId").asText()),"Owner",
                otherId,UUID.fromString(other.get("userId").asText()),"Other");
        String property=postJson("/api/v1/owner/properties",ownerToken,
                Map.of("name","Photo PG","description","","address","Address","area","OMR","city","Chennai","active",true),200).get("id").asText();
        String path="/api/v1/owner/properties/"+property+"/photos";
        var image=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",image);
        var file=new MockMultipartFile("file","image.png","image/png",image.toByteArray());
        mvc.perform(multipart(path).file(file).header("Authorization","Bearer "+otherToken)).andExpect(status().isNotFound());
        mvc.perform(multipart(path).file(new MockMultipartFile("file","fake.png","image/png",new byte[]{1,2,3}))
                .header("Authorization","Bearer "+ownerToken)).andExpect(status().isBadRequest());
        mvc.perform(multipart(path).file(new MockMultipartFile("file","large.png","image/png",new byte[5*1024*1024+1]))
                .header("Authorization","Bearer "+ownerToken)).andExpect(status().isBadRequest());
        String first=json.readTree(mvc.perform(multipart(path).file(file).header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String second=json.readTree(mvc.perform(multipart(path).file(file).header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/v1/properties/"+property+"/photos/"+first))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(image.toByteArray()));
        mvc.perform(put(path+"/order").header("Authorization","Bearer "+ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("photoIds",List.of(first,first)))))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path+"/"+second+"/cover").header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].cover").value(true));
        mvc.perform(put(path+"/order").header("Authorization","Bearer "+ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("photoIds",List.of(first,second)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(first));
        mvc.perform(delete(path+"/"+first).header("Authorization","Bearer "+otherToken)).andExpect(status().isNotFound());
        mvc.perform(delete(path+"/"+first).header("Authorization","Bearer "+ownerToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/properties/"+property+"/photos"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].cover").value(true));
        for (int i=0; i<9; i++)
            mvc.perform(multipart(path).file(file).header("Authorization","Bearer "+ownerToken)).andExpect(status().isOk());
        mvc.perform(multipart(path).file(file).header("Authorization","Bearer "+ownerToken)).andExpect(status().isBadRequest());
        jdbc.update("UPDATE properties SET active=false WHERE id=?",UUID.fromString(property));
        mvc.perform(get("/api/v1/properties/"+property+"/photos/"+second)).andExpect(status().isNotFound());
        mvc.perform(get(path+"/"+second).header("Authorization","Bearer "+ownerToken)).andExpect(status().isOk());
    }

    @Test
    void filtersAmenitiesAndSavedPropertiesAreScopedToUsers() throws Exception {
        JsonNode owner=signIn("+919000000008"), other=signIn("+919000000009");
        String ownerToken=owner.get("accessToken").asText(), otherToken=other.get("accessToken").asText();
        UUID ownerId=UUID.randomUUID();
        jdbc.update("INSERT INTO owner_profiles(id,user_id,display_name) VALUES (?,?,?)",
                ownerId,UUID.fromString(owner.get("userId").asText()),"Owner");
        String property=postJson("/api/v1/owner/properties",ownerToken,
                Map.of("name","Filter PG","description","","address","Address","area","OMR","city","Chennai","active",true),200).get("id").asText();
        String amenityPath="/api/v1/owner/properties/"+property+"/amenities";
        mvc.perform(put(amenityPath).header("Authorization","Bearer "+otherToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"codes\":[\"wifi\"]}")).andExpect(status().isForbidden());
        mvc.perform(put(amenityPath).header("Authorization","Bearer "+ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"codes\":[\"wifi\",\"meals\"]}")).andExpect(status().isOk());
        mvc.perform(put(amenityPath).header("Authorization","Bearer "+ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"codes\":[\"unknown\"]}")).andExpect(status().isBadRequest());
        mvc.perform(get(amenityPath).header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        String cheap=postJson("/api/v1/owner/properties/"+property+"/rooms",ownerToken,Map.of("name","Cheap","monthlyRent","5000"),200).get("id").asText();
        String expensive=postJson("/api/v1/owner/properties/"+property+"/rooms",ownerToken,Map.of("name","Expensive","monthlyRent","9000"),200).get("id").asText();
        postJson("/api/v1/owner/rooms/"+expensive+"/beds",ownerToken,Map.of("label","A"),200);
        mvc.perform(get("/api/v1/properties").param("query","Chennai").param("maxRent","6000").param("availableOnly","true")
                .param("amenity","wifi").param("amenity","meals"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        postJson("/api/v1/owner/rooms/"+cheap+"/beds",ownerToken,Map.of("label","B"),200);
        mvc.perform(get("/api/v1/properties").param("query","Chennai").param("maxRent","6000").param("availableOnly","true")
                .param("amenity","wifi").param("amenity","meals"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(property));
        String saved="/api/v1/customer/saved-properties";
        mvc.perform(post(saved+"/"+property).header("Authorization","Bearer "+ownerToken)).andExpect(status().isOk());
        mvc.perform(post(saved+"/"+property).header("Authorization","Bearer "+ownerToken)).andExpect(status().isOk());
        mvc.perform(get(saved).header("Authorization","Bearer "+ownerToken)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(saved).header("Authorization","Bearer "+otherToken)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete(saved+"/"+property).header("Authorization","Bearer "+otherToken)).andExpect(status().isOk());
        mvc.perform(get(saved).header("Authorization","Bearer "+ownerToken)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete(saved+"/"+property).header("Authorization","Bearer "+ownerToken)).andExpect(status().isOk());
        mvc.perform(get(saved).header("Authorization","Bearer "+ownerToken)).andExpect(jsonPath("$.length()").value(0));
    }
}
