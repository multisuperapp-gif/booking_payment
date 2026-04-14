package com.msa.booking.payment.modules.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class PayoutBatchDtos {
    private PayoutBatchDtos() {
    }

    public record CreatePayoutBatchRequest(
            String beneficiaryType,
            Integer limit,
            List<Long> settlementIds
    ) {
    }

    public record CompletePayoutBatchRequest(
            List<Long> settlementIds
    ) {
    }

    public record FailPayoutBatchRequest(
            String reason,
            List<Long> settlementIds
    ) {
    }

    public record PayoutBatchItemData(
            Long payoutBatchItemId,
            Long settlementId,
            BigDecimal payoutAmount,
            String payoutStatus,
            String failureReason
    ) {
    }

    public record PayoutBatchData(
            Long payoutBatchId,
            String batchReference,
            String payoutStatus,
            BigDecimal totalAmount,
            int settlementCount,
            LocalDateTime initiatedAt,
            LocalDateTime completedAt,
            List<PayoutBatchItemData> items
    ) {
    }
}
