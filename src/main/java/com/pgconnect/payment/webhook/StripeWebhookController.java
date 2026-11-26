package com.pgconnect.payment.webhook;

import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
public class StripeWebhookController {
  private final StripeWebhookVerifier verifier;

  public StripeWebhookController(StripeWebhookVerifier verifier) {
    this.verifier = verifier;
  }

  @PostMapping
  public ResponseEntity<Void> receive(@RequestBody String payload,
                                      @RequestHeader(value = "stripe-signature", required = false) String signature) {
    if (signature == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    try {
      Event event = verifier.verifyAndParse(payload, signature);

      switch (event.getType()) {
        case "payment_intent.succeeded":
          handlePaymentIntentSucceeded(event);
          break;
        case "payment_intent.payment_failed":
          handlePaymentIntentFailed(event);
          break;
        case "charge.refunded":
          handleChargeRefunded(event);
          break;
        default:
          // Ignore unhandled event types
          break;
      }

      return ResponseEntity.ok().build();
    } catch (SecurityException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  private void handlePaymentIntentSucceeded(Event event) {
    PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer().getObject().orElse(null);
    if (intent != null) {
      String orderId = intent.getMetadata().get("order_id");
      System.out.println("Payment succeeded for order: " + orderId);
      // TODO: Update payment order status to PAID
    }
  }

  private void handlePaymentIntentFailed(Event event) {
    PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer().getObject().orElse(null);
    if (intent != null) {
      String orderId = intent.getMetadata().get("order_id");
      System.out.println("Payment failed for order: " + orderId);
      // TODO: Update payment order status to FAILED
    }
  }

  private void handleChargeRefunded(Event event) {
    com.stripe.model.Charge charge = (com.stripe.model.Charge) event.getDataObjectDeserializer().getObject().orElse(null);
    if (charge != null) {
      System.out.println("Charge refunded: " + charge.getId());
      // TODO: Create refund record
    }
  }
}
