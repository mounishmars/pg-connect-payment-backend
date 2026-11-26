package com.pgconnect.payment.gateway;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Charge;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.ChargeCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
public class StripeClient {
  public StripeClient(@Value("${stripe.api-key}") String apiKey) {
    if (apiKey != null && !apiKey.isEmpty()) {
      Stripe.apiKey = apiKey;
    }
  }

  public StripePaymentIntent createPaymentIntent(String orderId, BigDecimal amount, String customerId, String customerEmail) throws StripeException {
    if (Stripe.apiKey == null || Stripe.apiKey.isEmpty()) {
      throw new IllegalStateException("Stripe API key not configured. Set STRIPE_API_KEY environment variable.");
    }

    long amountInCents = amount.multiply(new BigDecimal("100")).longValue();

    PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
      .setAmount(amountInCents)
      .setCurrency("inr")
      .setDescription("Rent Invoice: " + orderId)
      .putMetadata("order_id", orderId)
      .putMetadata("customer_id", customerId)
      .setStatementDescriptorSuffix("PG Connect")
      .build();

    PaymentIntent intent = PaymentIntent.create(params);
    return new StripePaymentIntent(intent.getId(), intent.getClientSecret());
  }

  public StripeCharge getCharge(String chargeId) throws StripeException {
    Charge charge = Charge.retrieve(chargeId);
    return new StripeCharge(
      charge.getId(),
      charge.getAmount() / 100.0,
      charge.getStatus(),
      charge.getReceiptEmail(),
      charge.getPaymentMethod()
    );
  }

  public record StripePaymentIntent(String intentId, String clientSecret) {}

  public record StripeCharge(String chargeId, Double amount, String status, String email, String paymentMethod) {}
}
