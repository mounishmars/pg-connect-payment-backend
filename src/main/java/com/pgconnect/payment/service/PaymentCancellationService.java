package com.pgconnect.payment.service;

import com.pgconnect.payment.domain.PaymentOrder;
import com.pgconnect.payment.gateway.StripeClient;
import com.pgconnect.payment.repo.PaymentOrderRepository;
import com.pgconnect.payment.repo.RentInvoiceRepository;
import com.stripe.exception.StripeException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class PaymentCancellationService {
  private final JdbcTemplate jdbc;
  private final RentInvoiceRepository invoices;
  private final PaymentOrderRepository orders;
  private final StripeClient stripe;
  public PaymentCancellationService(JdbcTemplate jdbc, RentInvoiceRepository invoices,
                                    PaymentOrderRepository orders, StripeClient stripe) {
    this.jdbc=jdbc; this.invoices=invoices; this.orders=orders; this.stripe=stripe;
  }

  @Transactional(rollbackFor=StripeException.class)
  public void cancel(UUID id, UUID user) throws StripeException {
    var rows=jdbc.query("SELECT rent_invoice_id FROM payment_orders WHERE id=? AND payer_user_id=? AND gateway='STRIPE'",
        (rs,row)->rs.getObject(1,UUID.class), id, user);
    if(rows.isEmpty()) throw new NoSuchElementException("Payment not found");
    invoices.findByIdForUpdate(rows.get(0)).orElseThrow(() -> new NoSuchElementException("Invoice not found"));
    PaymentOrder order=orders.findById(id).orElseThrow(() -> new NoSuchElementException("Payment not found"));
    if ("CANCELLED".equals(order.getStatus())) return;
    if (!"ACTIVE".equals(order.getStatus()) || order.getGatewayOrderId()==null)
      throw new IllegalStateException("Payment cannot be cancelled");
    var intent=stripe.retrievePaymentIntent(order.getGatewayOrderId());
    if (!Boolean.FALSE.equals(intent.getLivemode())) throw new IllegalStateException("Not a Stripe test payment");
    if (!"canceled".equals(intent.getStatus())) intent=stripe.cancelPaymentIntent(order.getGatewayOrderId());
    if (!Boolean.FALSE.equals(intent.getLivemode()) || !"canceled".equals(intent.getStatus()))
      throw new IllegalStateException("Stripe did not confirm cancellation");
    order.markCancelled();
  }
}
