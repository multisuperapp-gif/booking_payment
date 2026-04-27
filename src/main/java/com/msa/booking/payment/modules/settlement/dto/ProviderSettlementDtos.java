package com.msa.booking.payment.modules.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ProviderSettlementDtos {
    private ProviderSettlementDtos() {
    }

    public record ProviderSettlementRecordData(
            Long settlementId,
            String settlementCode,
            Long bookingId,
            String bookingCode,
            String bookingStatus,
            String paymentStatus,
            String categoryLabel,
            String labourPricingModel,
            BigDecimal quotedPriceAmount,
            BigDecimal platformFeeAmount,
            BigDecimal providerShareAmount,
            String settlementStatus,
            String payoutStatus,
            String payoutBatchReference,
            LocalDate cycleStart,
            LocalDate cycleEnd,
            LocalDateTime createdAt,
            LocalDateTime paidAt,
            String remarks,
            String statementObjectKey,
            String statementAccessUrl
    ) {
    }

    public record ProviderSettlementDashboardData(
            String settlementCadence,
            LocalDate currentWeekStart,
            LocalDate currentWeekEnd,
            LocalDateTime nextPayoutAt,
            BigDecimal pendingAmount,
            BigDecimal paidThisWeekAmount,
            BigDecimal lastPayoutAmount,
            LocalDateTime lastPayoutAt,
            int eligibleRecordCount,
            List<ProviderSettlementRecordData> records
    ) {
    }
}
