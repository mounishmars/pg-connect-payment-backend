package com.pgconnect.payment.api;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.pgconnect.payment.domain.PaymentOrder;
import com.pgconnect.payment.service.RentPaymentService;
import com.pgconnect.payment.service.StripePaymentProcessor;
import com.pgconnect.payment.service.PaymentCancellationService;
import com.pgconnect.payment.gateway.StripeClient;
import com.pgconnect.payment.repo.PaymentOrderRepository;
import com.pgconnect.payment.repo.RentInvoiceRepository;
import com.stripe.exception.StripeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.NoSuchElementException;
import java.util.List;

@RestController @RequestMapping("/api/v1/customer/rent/payments")
@SecurityRequirement(name="bearerAuth")
@Validated
public class RentPaymentController {
 private final RentPaymentService service;
 private final PaymentOrderRepository orders;
 private final RentInvoiceRepository invoices;
 private final StripeClient stripe;
 private final StripePaymentProcessor processor;
 private final PaymentCancellationService cancellation;
 public RentPaymentController(RentPaymentService service, PaymentOrderRepository orders, RentInvoiceRepository invoices,
                              StripeClient stripe, StripePaymentProcessor processor, PaymentCancellationService cancellation) {
   this.service=service; this.orders=orders; this.invoices=invoices; this.stripe=stripe; this.processor=processor; this.cancellation=cancellation;
 }

 @GetMapping("/active")
 public org.springframework.http.ResponseEntity<CreatePaymentResponse> active(
     @AuthenticationPrincipal UUID user, @RequestParam UUID invoiceId) {
   var invoice=invoices.findById(invoiceId).orElseThrow(() -> new NoSuchElementException("Invoice not found"));
   if (!invoice.getTenantUserId().equals(user)) throw new SecurityException("Invoice does not belong to user");
   var order=orders.findByRentInvoiceIdAndGatewayAndStatusIn(invoiceId,"STRIPE",List.of("ACTIVE"));
   if (order.isEmpty()) return org.springframework.http.ResponseEntity.noContent().build();
   PaymentOrder found=order.get();
   if (!found.getPayerUserId().equals(user)) throw new SecurityException("Payment does not belong to user");
   return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
       .body(new CreatePaymentResponse(found.getId(),found.getOrderReference(),found.getAmount(),found.getStatus(),found.getPaymentSessionId()));
 }

 @PostMapping("/{id}/cancel")
 public void cancel(@AuthenticationPrincipal UUID user, @PathVariable UUID id) throws StripeException {
   cancellation.cancel(id,user);
 }
 
 @PostMapping 
 public CreatePaymentResponse create(@AuthenticationPrincipal UUID authenticatedUser,
   @RequestHeader("Idempotency-Key") @NotBlank @Size(max=100) String idempotencyKey,
   @Valid @RequestBody CreatePaymentRequest request) throws StripeException{
  PaymentOrder order=service.createOrder(request.invoiceId(),authenticatedUser,request.amount(),idempotencyKey,request.customerEmail());
  return new CreatePaymentResponse(order.getId(),order.getOrderReference(),order.getAmount(),order.getStatus(),order.getPaymentSessionId());
 }

 @PostMapping("/{id}/reconcile")
 public PaymentStatusResponse reconcile(@AuthenticationPrincipal UUID user, @PathVariable UUID id) throws StripeException {
   PaymentOrder order=orders.findById(id).orElseThrow(() -> new NoSuchElementException("Payment not found"));
   if (!order.getPayerUserId().equals(user)) throw new SecurityException("Payment does not belong to user");
   if (!"STRIPE".equals(order.getGateway()) || order.getGatewayOrderId() == null)
     throw new IllegalStateException("Payment does not have a Stripe session");
   var intent=stripe.retrievePaymentIntent(order.getGatewayOrderId());
   if ("succeeded".equals(intent.getStatus())) processor.applySucceeded(intent);
   PaymentOrder updated=orders.findById(id).orElseThrow(() -> new NoSuchElementException("Payment not found"));
   return new PaymentStatusResponse(updated.getId(),updated.getStatus());
 }

 public record PaymentStatusResponse(UUID paymentOrderId, String status) {}
 
 public record CreatePaymentRequest(
   @NotNull UUID invoiceId,
   @NotNull @DecimalMin("0.01") BigDecimal amount,
   @NotBlank @Email String customerEmail
 ){}
 
 public record CreatePaymentResponse(
   UUID paymentOrderId,
   String orderReference,
   BigDecimal amount,
   String status,
   String paymentSessionId
 ){}
}
