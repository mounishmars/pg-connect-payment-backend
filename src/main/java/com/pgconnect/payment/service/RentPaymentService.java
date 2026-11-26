package com.pgconnect.payment.service;

import com.pgconnect.payment.domain.PaymentOrder;
import com.pgconnect.payment.gateway.StripeClient;
import com.stripe.exception.StripeException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.UUID;

@Service
public class RentPaymentService {
  private final PaymentOrderReservation reservations;
  private final StripeClient stripe;
  public RentPaymentService(PaymentOrderReservation reservations, StripeClient stripe) {
    this.reservations=reservations; this.stripe=stripe;
  }

  public PaymentOrder createOrder(UUID invoiceId, UUID user, BigDecimal amount, String key, String customerEmail)
      throws StripeException {
    stripe.requireTestCheckout();
    PaymentOrder order=reservations.reserve(invoiceId,user,amount,key);
    if (!"CREATED".equals(order.getStatus())) return order;
    StripeClient.StripePaymentIntent intent=stripe.createPaymentIntent(order.getOrderReference(),order.getAmount(),
        user.toString(),customerEmail,"pgconnect-"+order.getId());
    return reservations.activate(invoiceId,order.getId(),intent);
  }
}
