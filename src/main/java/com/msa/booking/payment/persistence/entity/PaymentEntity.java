package com.msa.booking.payment.persistence.entity;

import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@Setter
public class PaymentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_code", nullable = false, unique = true, length = 32)
    private String paymentCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "payable_type", nullable = false, length = 20)
    private PayableType payableType;

    @Column(name = "payable_id", nullable = false)
    private Long payableId;

    @Column(name = "payer_user_id", nullable = false)
    private Long payerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    private PaymentLifecycleStatus paymentStatus;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

}
