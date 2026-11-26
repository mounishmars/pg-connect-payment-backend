package com.pgconnect.payment.service;

import com.pgconnect.payment.domain.PaymentOrder;
import com.pgconnect.payment.domain.PaymentTransaction;
import com.pgconnect.payment.repo.PaymentOrderRepository;
import com.pgconnect.payment.repo.PaymentTransactionRepository;
import com.pgconnect.payment.repo.RentInvoiceRepository;
import com.stripe.model.PaymentIntent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class StripePaymentProcessor {
  private final PaymentOrderRepository orders;
  private final RentInvoiceRepository invoices;
  private final PaymentTransactionRepository transactions;

  public StripePaymentProcessor(PaymentOrderRepository orders, RentInvoiceRepository invoices,
                                PaymentTransactionRepository transactions) {
    this.orders=orders; this.invoices=invoices; this.transactions=transactions;
  }

  @Transactional
  public void applySucceeded(PaymentIntent intent) {
    if (!Boolean.FALSE.equals(intent.getLivemode()) || !"succeeded".equals(intent.getStatus())
        || intent.getAmountReceived() == null || intent.getId() == null) {
      throw new IllegalStateException("Stripe payment is not a successful test-mode payment");
    }
    PaymentOrder order = orders.findByGatewayOrderId(intent.getId())
        .orElseThrow(() -> new NoSuchElementException("Payment order not yet available"));
    var invoice = invoices.findByIdForUpdate(order.getRentInvoiceId())
        .orElseThrow(() -> new NoSuchElementException("Invoice not found"));
    BigDecimal received = BigDecimal.valueOf(intent.getAmountReceived(), 2);
    if (!"STRIPE".equals(order.getGateway()) || !"inr".equalsIgnoreCase(intent.getCurrency()) || received.compareTo(order.getAmount()) != 0
        || intent.getMetadata() == null || !order.getOrderReference().equals(intent.getMetadata().get("order_id"))
        || !order.getPayerUserId().toString().equals(intent.getMetadata().get("customer_id"))) {
      throw new IllegalStateException("Stripe payment does not match the payment order");
    }
    if (transactions.existsByGatewayPaymentId(intent.getId())) return;
    if (!"ACTIVE".equals(order.getStatus()) || "VOID".equals(invoice.getStatus())
        || received.compareTo(invoice.getBalance()) > 0) {
      throw new IllegalStateException("Payment cannot be applied to this invoice");
    }
    transactions.save(new PaymentTransaction(UUID.randomUUID(), order.getId(), intent.getId(), received, null, OffsetDateTime.now()));
    invoice.applyPayment(received);
    order.markPaid();
  }
}
