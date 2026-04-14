package com.msa.booking.payment.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payment_webhook_events")
@Getter
@Setter
public class PaymentWebhookEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_system", nullable = false, length = 40)
    private String sourceSystem;

    @Column(name = "webhook_key", nullable = false, length = 128)
    private String webhookKey;

    @Column(name = "event_type", length = 120)
    private String eventType;

    @Column(name = "gateway_order_id", length = 120)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 120)
    private String gatewayPaymentId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    @Column(name = "message", length = 255)
    private String message;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
