package com.msa.booking.payment.persistence.entity;

import com.msa.booking.payment.domain.enums.PaymentTransactionStatus;
import com.msa.booking.payment.domain.enums.PaymentTransactionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
public class PaymentTransactionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "gateway_transaction_id", nullable = false, unique = true, length = 150)
    private String gatewayTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private PaymentTransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_status", nullable = false, length = 20)
    private PaymentTransactionStatus transactionStatus;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

}
