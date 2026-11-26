package com.pgconnect.payment.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="payment_webhook_events")
public class WebhookEvent {
    @Id private UUID id;
    @Column(nullable=false) private String gateway;
    @Column(name="gateway_event_id",nullable=false) private String gatewayEventId;
    @Column(name="event_type",nullable=false) private String eventType;
    @Column(nullable=false,columnDefinition="jsonb") private String payload;
    @Column(name="signature_verified",nullable=false) private boolean signatureVerified;
    @Column(name="processing_status",nullable=false) private String processingStatus;
    @Column(name="received_at",insertable=false,updatable=false) private OffsetDateTime receivedAt;
    protected WebhookEvent() {}
    public WebhookEvent(String gateway,String eventId,String type,String payload){this.id=UUID.randomUUID();this.gateway=gateway;this.gatewayEventId=eventId;this.eventType=type;this.payload=payload;this.signatureVerified=true;this.processingStatus="RECEIVED";}
}
