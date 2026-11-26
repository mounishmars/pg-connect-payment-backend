package com.pgconnect.payment.webhook;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StripeWebhookVerifier {
  private final String webhookSecret;

  public StripeWebhookVerifier(@Value("${stripe.webhook-secret}") String webhookSecret) {
    this.webhookSecret = webhookSecret;
  }

  public Event verifyAndParse(String payload, String signature) {
    if (webhookSecret == null || webhookSecret.isEmpty()) {
      throw new IllegalStateException("Stripe webhook secret not configured. Set STRIPE_WEBHOOK_SECRET environment variable.");
    }

    try {
      return Webhook.constructEvent(payload, signature, webhookSecret);
    } catch (SignatureVerificationException e) {
      throw new SecurityException("Invalid webhook signature: " + e.getMessage(), e);
    }
  }
}
