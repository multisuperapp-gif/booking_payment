package com.msa.booking.payment.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
        name = "payout_batch_items",
        indexes = {
                @Index(name = "idx_payout_batch_items_batch_id", columnList = "payout_batch_id"),
                @Index(name = "idx_payout_batch_items_bank_account_id", columnList = "bank_account_id")
        }
)
@Getter
@Setter
public class PayoutBatchItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payout_batch_id", nullable = false)
    private Long payoutBatchId;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "bank_account_id")
    private Long bankAccountId;

    @Column(name = "payout_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal payoutAmount;

    @Column(name = "payout_status", nullable = false, length = 20)
    private String payoutStatus;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;
}
