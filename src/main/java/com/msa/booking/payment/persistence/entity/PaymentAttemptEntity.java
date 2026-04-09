package com.msa.booking.payment.persistence.entity;

import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "payment_attempts",
        indexes = {
                @Index(name = "idx_payment_attempts_payment_id", columnList = "payment_id"),
                @Index(name = "idx_payment_attempts_gateway_order_id", columnList = "gateway_order_id")
        }
)
@Getter
@Setter
public class PaymentAttemptEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "gateway_name", nullable = false, length = 60)
    private String gatewayName;

    @Column(name = "gateway_order_id", length = 120)
    private String gatewayOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "attempt_status", nullable = false, length = 20)
    private PaymentAttemptStatus attemptStatus;

    @Column(name = "requested_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "response_code", length = 50)
    private String responseCode;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
