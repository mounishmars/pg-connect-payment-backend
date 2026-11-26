package com.pgconnect.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="payment_orders")
public class PaymentOrder {
    @Id private UUID id;
    @Column(name="order_reference",nullable=false) private String orderReference;
    @Column(name="rent_invoice_id",nullable=false) private UUID rentInvoiceId;
    @Column(name="payer_user_id",nullable=false) private UUID payerUserId;
    @Column(name="owner_id",nullable=false) private UUID ownerId;
    @Column(nullable=false) private String gateway;
    @Column(name="gateway_order_id") private String gatewayOrderId;
    @Column(name="payment_session_id") private String paymentSessionId;
    @Column(nullable=false,precision=14,scale=2) private BigDecimal amount;
    @Column(nullable=false) private String currency;
    @Column(nullable=false) private String status;
    @Column(name="idempotency_key",nullable=false) private String idempotencyKey;
    @Column(name="created_at",insertable=false,updatable=false) private OffsetDateTime createdAt;
    protected PaymentOrder() {}
    public PaymentOrder(UUID id,String ref,UUID invoiceId,UUID payerId,UUID ownerId,BigDecimal amount,String idem){
      this.id=id;
      this.orderReference=ref;
      this.rentInvoiceId=invoiceId;
      this.payerUserId=payerId;
      this.ownerId=ownerId;
      this.amount=amount;
      this.idempotencyKey=idem;
      this.gateway="STRIPE";
      this.currency="INR";
      this.status="CREATED";
    }
    public UUID getId(){return id;} 
    public String getOrderReference(){return orderReference;} 
    public UUID getRentInvoiceId(){return rentInvoiceId;}
    public UUID getPayerUserId(){return payerUserId;} 
    public UUID getOwnerId(){return ownerId;} 
    public BigDecimal getAmount(){return amount;}
    public String getGatewayOrderId(){return gatewayOrderId;} 
    public String getGateway(){return gateway;}
    public String getStatus(){return status;} 
    public String getPaymentSessionId(){return paymentSessionId;}
    public void activate(String paymentIntentId,String clientSecret){
      gatewayOrderId=paymentIntentId;
      paymentSessionId=clientSecret;
      status="ACTIVE";
    }
    public void markPaid(){status="PAID";}
    public void markCancelled(){status="CANCELLED";}
    public void markFailed(){status="FAILED";}
}
