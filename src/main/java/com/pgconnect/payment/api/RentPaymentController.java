package com.pgconnect.payment.api;

import com.pgconnect.payment.domain.PaymentOrder;
import com.pgconnect.payment.service.RentPaymentService;
import com.stripe.exception.StripeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/customer/rent/payments")
public class RentPaymentController {
 private final RentPaymentService service;
 public RentPaymentController(RentPaymentService service){this.service=service;}
 
 @PostMapping 
 public CreatePaymentResponse create(@RequestHeader("X-Authenticated-User") UUID authenticatedUser,
   @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
   @Valid @RequestBody CreatePaymentRequest request) throws StripeException{
  PaymentOrder order=service.createOrder(request.invoiceId(),authenticatedUser,request.amount(),idempotencyKey,request.customerEmail());
  return new CreatePaymentResponse(order.getId(),order.getOrderReference(),order.getAmount(),order.getStatus(),order.getPaymentSessionId());
 }
 
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
