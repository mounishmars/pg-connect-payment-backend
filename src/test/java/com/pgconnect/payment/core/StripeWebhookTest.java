package com.pgconnect.payment.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgconnect.payment.gateway.StripeClient;
import com.pgconnect.payment.service.StripePaymentProcessor;
import com.pgconnect.payment.webhook.StripeWebhookController;
import com.pgconnect.payment.webhook.StripeWebhookVerifier;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StripeWebhookTest {
  @Test
  void checksSignatureBeforeFetchingAuthoritativePayment() throws Exception {
    String secret="whsec_dummy_for_test";
    StripeClient stripe=mock(StripeClient.class);
    StripePaymentProcessor processor=mock(StripePaymentProcessor.class);
    var mvc=MockMvcBuilders.standaloneSetup(new StripeWebhookController(
        new StripeWebhookVerifier(secret),stripe,processor,new ObjectMapper())).build();
    String body="{\"id\":\"evt_test\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_test\",\"object\":\"payment_intent\"}}}";
    long timestamp=Instant.now().getEpochSecond();
    Mac mac=Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    String hash=HexFormat.of().formatHex(mac.doFinal((timestamp+"."+body).getBytes(StandardCharsets.UTF_8)));
    PaymentIntent intent=new PaymentIntent();
    when(stripe.retrievePaymentIntent("pi_test")).thenReturn(intent);
    mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
        .header("stripe-signature","t="+timestamp+",v1="+hash).content(body)).andExpect(status().isOk());
    verify(processor).applySucceeded(intent);
    mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
        .header("stripe-signature","t="+timestamp+",v1=invalid").content(body)).andExpect(status().isUnauthorized());
    verifyNoMoreInteractions(processor);
  }
}
