package com.pgconnect.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgconnect.payment.gateway.StripeClient;
import com.pgconnect.payment.service.StripePaymentProcessor;
import com.stripe.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
public class StripeWebhookController {
  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
  private final StripeWebhookVerifier verifier;
  private final StripeClient stripe;
  private final StripePaymentProcessor processor;
  private final ObjectMapper mapper;

  public StripeWebhookController(StripeWebhookVerifier verifier, StripeClient stripe,
                                 StripePaymentProcessor processor, ObjectMapper mapper) {
    this.verifier=verifier; this.stripe=stripe; this.processor=processor; this.mapper=mapper;
  }

  @PostMapping
  public ResponseEntity<Void> receive(@RequestBody String payload,
      @RequestHeader(value="stripe-signature",required=false) String signature) throws Exception {
    if (signature == null) return ResponseEntity.badRequest().build();
    Event event;
    try {
      event = verifier.verifyAndParse(payload,signature);
    } catch (SecurityException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    if ("payment_intent.succeeded".equals(event.getType())) {
      JsonNode object = mapper.readTree(event.getDataObjectDeserializer().getRawJson());
      String intentId = object.path("id").asText();
      if (intentId.isBlank()) throw new IllegalStateException("Stripe event has no payment intent ID");
      processor.applySucceeded(stripe.retrievePaymentIntent(intentId));
      log.info("Processed Stripe payment event {}", event.getId());
    }
    return ResponseEntity.ok().build();
  }
}
