package com.pgconnect.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="payment_transactions")
public class PaymentTransaction {
    @Id private UUID id;
    @Column(name="payment_order_id",nullable=false) private UUID paymentOrderId;
    @Column(name="gateway_payment_id",nullable=false) private String gatewayPaymentId;
    @Column(name="payment_method") private String paymentMethod;
    @Column(nullable=false,precision=14,scale=2) private BigDecimal amount;
    @Column(nullable=false) private String currency;
    @Column(nullable=false) private String status;
    @Column(name="gateway_fee",nullable=false) private BigDecimal gatewayFee;
    @Column(name="gateway_tax",nullable=false) private BigDecimal gatewayTax;
    @Column(name="paid_at") private OffsetDateTime paidAt;
    protected PaymentTransaction() {}
    public PaymentTransaction(UUID id,UUID orderId,String gatewayPaymentId,BigDecimal amount,String method,OffsetDateTime paidAt){this.id=id;this.paymentOrderId=orderId;this.gatewayPaymentId=gatewayPaymentId;this.amount=amount;this.paymentMethod=method;this.paidAt=paidAt;this.currency="INR";this.status="SUCCESS";this.gatewayFee=BigDecimal.ZERO;this.gatewayTax=BigDecimal.ZERO;}
}
