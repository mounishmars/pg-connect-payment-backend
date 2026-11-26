package com.pgconnect.payment.service;

import com.pgconnect.payment.domain.*;
import com.pgconnect.payment.gateway.StripeClient;
import com.pgconnect.payment.repo.*;
import com.stripe.exception.StripeException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.util.*;

@Service
public class RentPaymentService {
 private final RentInvoiceRepository invoices; 
 private final PaymentOrderRepository orders; 
 private final StripeClient stripe;
 
 public RentPaymentService(RentInvoiceRepository invoices,PaymentOrderRepository orders,StripeClient stripe){
   this.invoices=invoices;
   this.orders=orders;
   this.stripe=stripe;
 }
 
 @Transactional
 public PaymentOrder createOrder(UUID invoiceId,UUID authenticatedUserId,BigDecimal requestedAmount,String idempotencyKey,String customerEmail) throws StripeException{
  var existing=orders.findByIdempotencyKey(idempotencyKey); 
  if(existing.isPresent()) return existing.get();
  
  RentInvoice invoice=invoices.findByIdForUpdate(invoiceId).orElseThrow(()->new NoSuchElementException("Invoice not found"));
  if(!invoice.getTenantUserId().equals(authenticatedUserId)) throw new SecurityException("Invoice does not belong to authenticated tenant");
  
  BigDecimal amount=requestedAmount.setScale(2,RoundingMode.UNNECESSARY);
  if(amount.signum()<=0 || amount.compareTo(invoice.getBalance())>0) throw new IllegalArgumentException("Amount must be positive and no greater than outstanding balance");
  if("PAID".equals(invoice.getStatus()) || "VOID".equals(invoice.getStatus())) throw new IllegalStateException("Invoice is not payable");
  
  UUID id=UUID.randomUUID(); 
  String ref="PGCR-"+id.toString().substring(0,12).toUpperCase();
  PaymentOrder order=new PaymentOrder(id,ref,invoice.getId(),authenticatedUserId,invoice.getOwnerId(),amount,idempotencyKey);
  orders.saveAndFlush(order);
  
  StripeClient.StripePaymentIntent intent=stripe.createPaymentIntent(ref,amount,authenticatedUserId.toString(),customerEmail);
  order.activate(intent.intentId(),intent.clientSecret());
  return orders.saveAndFlush(order);
 }
}
