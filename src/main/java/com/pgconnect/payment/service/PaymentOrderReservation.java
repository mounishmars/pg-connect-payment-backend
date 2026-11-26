package com.pgconnect.payment.service;

import com.pgconnect.payment.domain.PaymentOrder;
import com.pgconnect.payment.gateway.StripeClient;
import com.pgconnect.payment.repo.PaymentOrderRepository;
import com.pgconnect.payment.repo.RentInvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class PaymentOrderReservation {
  private final RentInvoiceRepository invoices;
  private final PaymentOrderRepository orders;
  public PaymentOrderReservation(RentInvoiceRepository invoices, PaymentOrderRepository orders) {
    this.invoices=invoices; this.orders=orders;
  }

  @Transactional
  public PaymentOrder reserve(UUID invoiceId, UUID user, BigDecimal requestedAmount, String key) {
    var existing=orders.findByIdempotencyKey(key);
    if (existing.isPresent()) return validateExisting(existing.get(),invoiceId,user,requestedAmount);
    var invoice=invoices.findByIdForUpdate(invoiceId).orElseThrow(() -> new NoSuchElementException("Invoice not found"));
    if (!invoice.getTenantUserId().equals(user)) throw new SecurityException("Invoice does not belong to authenticated tenant");
    existing=orders.findByIdempotencyKey(key);
    if (existing.isPresent()) return validateExisting(existing.get(),invoiceId,user,requestedAmount);
    BigDecimal amount=requestedAmount.setScale(2,RoundingMode.UNNECESSARY);
    if (amount.signum()<=0 || amount.compareTo(invoice.getBalance())>0)
      throw new IllegalArgumentException("Amount must be positive and no greater than outstanding balance");
    if ("PAID".equals(invoice.getStatus()) || "VOID".equals(invoice.getStatus()))
      throw new IllegalStateException("Invoice is not payable");
    var active=orders.findByRentInvoiceIdAndGatewayAndStatusIn(invoiceId,"STRIPE",List.of("CREATED","ACTIVE"));
    if (active.isPresent()) {
      PaymentOrder previous=active.get();
      if (!previous.getPayerUserId().equals(user) || previous.getAmount().compareTo(amount)!=0)
        throw new IllegalStateException("An unfinished payment already exists for this invoice; resume it before changing the amount");
      return previous;
    }
    UUID id=UUID.nameUUIDFromBytes((user+":"+key).getBytes(StandardCharsets.UTF_8));
    String reference="PGCR-"+id.toString().toUpperCase(Locale.ROOT);
    return orders.saveAndFlush(new PaymentOrder(id,reference,invoiceId,user,invoice.getOwnerId(),amount,key));
  }

  private PaymentOrder validateExisting(PaymentOrder order, UUID invoiceId, UUID user, BigDecimal amount) {
    if (!order.getPayerUserId().equals(user) || !order.getRentInvoiceId().equals(invoiceId)
        || order.getAmount().compareTo(amount)!=0)
      throw new IllegalArgumentException("Idempotency key already used for a different payment");
    return order;
  }

  @Transactional
  public PaymentOrder activate(UUID invoiceId, UUID orderId, StripeClient.StripePaymentIntent intent) {
    invoices.findByIdForUpdate(invoiceId).orElseThrow(() -> new NoSuchElementException("Invoice not found"));
    PaymentOrder order=orders.findById(orderId).orElseThrow(() -> new NoSuchElementException("Payment order not found"));
    if ("CREATED".equals(order.getStatus())) {
      order.activate(intent.intentId(),intent.clientSecret());
      return orders.saveAndFlush(order);
    }
    if (!intent.intentId().equals(order.getGatewayOrderId()))
      throw new IllegalStateException("Stripe returned a conflicting payment session");
    return order;
  }
}
