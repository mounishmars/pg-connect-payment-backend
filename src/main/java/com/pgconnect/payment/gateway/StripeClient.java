package com.pgconnect.payment.gateway;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class StripeClient {
  private final com.stripe.StripeClient client;
  private final boolean testCheckoutEnabled;

  public StripeClient(@Value("${stripe.api-key}") String apiKey,
                      @Value("${stripe.test-checkout-enabled:false}") boolean testCheckoutEnabled) {
    this.testCheckoutEnabled = testCheckoutEnabled;
    this.client = apiKey == null || apiKey.isBlank() ? null : new com.stripe.StripeClient(apiKey);
    if (testCheckoutEnabled && (apiKey == null || !(apiKey.startsWith("sk_test_") || apiKey.startsWith("rk_test_")))) {
      throw new IllegalStateException("Test checkout requires a Stripe test-mode API key");
    }
  }

  private com.stripe.StripeClient testClient() {
    if (!testCheckoutEnabled || client == null) throw new IllegalStateException("Stripe test checkout is not enabled");
    return client;
  }
  public void requireTestCheckout() { testClient(); }

  public StripePaymentIntent createPaymentIntent(String orderId, BigDecimal amount, String customerId,
                                                  String customerEmail, String idempotencyKey) throws StripeException {
    long minorUnits = amount.movePointRight(2).longValueExact();
    PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
        .setAmount(minorUnits).setCurrency("inr").setDescription("Rent invoice payment " + orderId)
        .putMetadata("order_id", orderId).putMetadata("customer_id", customerId)
        .build();
    PaymentIntent intent = testClient().v1().paymentIntents().create(params,
        RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
    if (!Boolean.FALSE.equals(intent.getLivemode()) || intent.getClientSecret() == null) {
      throw new IllegalStateException("Stripe did not return a test-mode payment session");
    }
    return new StripePaymentIntent(intent.getId(), intent.getClientSecret());
  }

  public PaymentIntent retrievePaymentIntent(String id) throws StripeException {
    return testClient().v1().paymentIntents().retrieve(id);
  }

  public PaymentIntent cancelPaymentIntent(String id) throws StripeException {
    return testClient().v1().paymentIntents().cancel(id,
        RequestOptions.builder().setIdempotencyKey("pgconnect-cancel-" + id).build());
  }

  public record StripePaymentIntent(String intentId, String clientSecret) {}
}
