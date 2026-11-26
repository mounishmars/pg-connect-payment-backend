package com.pgconnect.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="rent_invoices")
public class RentInvoice {
    @Id private UUID id;
    @Column(name="invoice_number",nullable=false,unique=true) private String invoiceNumber;
    @Column(name="tenant_user_id",nullable=false) private UUID tenantUserId;
    @Column(name="owner_id",nullable=false) private UUID ownerId;
    @Column(nullable=false) private String currency;
    @Column(name="total_amount",nullable=false,precision=14,scale=2) private BigDecimal totalAmount;
    @Column(name="amount_paid",nullable=false,precision=14,scale=2) private BigDecimal amountPaid;
    @Column(name="due_date",nullable=false) private LocalDate dueDate;
    @Column(nullable=false) private String status;
    @Column(name="created_at",insertable=false,updatable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at",insertable=false,updatable=false) private OffsetDateTime updatedAt;
    protected RentInvoice() {}
    public UUID getId(){return id;} public String getInvoiceNumber(){return invoiceNumber;}
    public UUID getTenantUserId(){return tenantUserId;} public UUID getOwnerId(){return ownerId;}
    public BigDecimal getTotalAmount(){return totalAmount;} public BigDecimal getAmountPaid(){return amountPaid;}
    public String getCurrency(){return currency;} public String getStatus(){return status;}
    public BigDecimal getBalance(){return totalAmount.subtract(amountPaid);}
    public void applyPayment(BigDecimal amount){
        if(amount.signum()<=0 || amount.compareTo(getBalance())>0) throw new IllegalArgumentException("Payment must be positive and not exceed invoice balance");
        amountPaid=amountPaid.add(amount); status=amountPaid.compareTo(totalAmount)==0?"PAID":"PARTIALLY_PAID";
    }
}
