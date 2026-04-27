package com.msa.booking.payment.modules.settlement.service;

import static com.msa.booking.payment.modules.settlement.dto.ProviderSettlementDtos.ProviderSettlementDashboardData;
import static com.msa.booking.payment.modules.settlement.dto.ProviderSettlementDtos.ProviderSettlementRecordData;

import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.persistence.entity.SettlementEntity;
import com.msa.booking.payment.persistence.repository.SettlementRepository;
import com.msa.booking.payment.persistence.repository.SettlementLineItemRepository;
import com.msa.booking.payment.storage.BillingDocumentLink;
import com.msa.booking.payment.storage.BillingDocumentStorageService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ProviderSettlementQueryService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final SettlementLineItemRepository settlementLineItemRepository;
    private final SettlementRepository settlementRepository;
    private final BillingDocumentStorageService billingDocumentStorageService;

    public ProviderSettlementQueryService(
            SettlementLineItemRepository settlementLineItemRepository,
            SettlementRepository settlementRepository,
            BillingDocumentStorageService billingDocumentStorageService
    ) {
        this.settlementLineItemRepository = settlementLineItemRepository;
        this.settlementRepository = settlementRepository;
        this.billingDocumentStorageService = billingDocumentStorageService;
    }

    public ProviderSettlementDashboardData weeklyDashboard(Long actingUserId, ProviderEntityType providerEntityType) {
        List<ProviderSettlementRecordData> records = settlementLineItemRepository.findProviderSettlementRecords(
                        providerEntityType == ProviderEntityType.LABOUR ? "LABOUR" : "PROVIDER",
                        providerEntityType.name(),
                        actingUserId
                ).stream()
                .map(this::toRecordData)
                .toList();

        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDateTime nextPayoutAt = resolveNextWeeklyPayoutAt();

        BigDecimal pendingAmount = ZERO;
        BigDecimal paidThisWeekAmount = ZERO;
        int eligibleRecordCount = 0;
        Map<String, _PayoutSnapshot> payoutSnapshots = new LinkedHashMap<>();

        for (ProviderSettlementRecordData record : records) {
            LocalDateTime paidAt = record.paidAt();
            if (paidAt == null) {
                pendingAmount = pendingAmount.add(money(record.providerShareAmount()));
                eligibleRecordCount++;
                continue;
            }
            LocalDate paidDate = paidAt.toLocalDate();
            if (!paidDate.isBefore(weekStart) && !paidDate.isAfter(weekEnd)) {
                paidThisWeekAmount = paidThisWeekAmount.add(money(record.providerShareAmount()));
            }
            String payoutKey = record.payoutBatchReference() != null && !record.payoutBatchReference().isBlank()
                    ? record.payoutBatchReference().trim()
                    : "SETTLEMENT-" + record.settlementId();
            _PayoutSnapshot current = payoutSnapshots.get(payoutKey);
            if (current == null) {
                payoutSnapshots.put(payoutKey, new _PayoutSnapshot(paidAt, money(record.providerShareAmount())));
            } else {
                payoutSnapshots.put(payoutKey, new _PayoutSnapshot(
                        paidAt.isAfter(current.paidAt()) ? paidAt : current.paidAt(),
                        current.amount().add(money(record.providerShareAmount()))
                ));
            }
        }

        _PayoutSnapshot lastPayout = payoutSnapshots.values().stream()
                .max(Comparator.comparing(_PayoutSnapshot::paidAt))
                .orElse(null);

        return new ProviderSettlementDashboardData(
                "WEEKLY",
                weekStart,
                weekEnd,
                nextPayoutAt,
                pendingAmount,
                paidThisWeekAmount,
                lastPayout == null ? ZERO : money(lastPayout.amount()),
                lastPayout == null ? null : lastPayout.paidAt(),
                eligibleRecordCount,
                List.copyOf(records)
        );
    }

    private LocalDateTime resolveNextWeeklyPayoutAt() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate candidateDate = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDateTime candidate = LocalDateTime.of(candidateDate, LocalTime.of(10, 0));
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }

    private LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private LocalDateTime resolvePaidAt(Timestamp settlementPaidAt, Timestamp payoutCompletedAt) {
        LocalDateTime payoutTime = toLocalDateTime(payoutCompletedAt);
        if (payoutTime != null) {
            return payoutTime;
        }
        return toLocalDateTime(settlementPaidAt);
    }

    private ProviderSettlementRecordData toRecordData(SettlementLineItemRepository.ProviderSettlementRecordView row) {
        BillingDocumentLink statementLink = settlementRepository.findById(row.getSettlementId())
                .map(billingDocumentStorageService::resolveSettlementStatementLink)
                .orElse(new BillingDocumentLink(null, null));
        return new ProviderSettlementRecordData(
                row.getSettlementId(),
                row.getSettlementCode(),
                row.getBookingId(),
                row.getBookingCode(),
                row.getBookingStatus(),
                row.getPaymentStatus(),
                row.getCategoryLabel(),
                row.getLabourPricingModel(),
                money(row.getQuotedPriceAmount()),
                money(row.getPlatformFeeAmount()),
                money(row.getProviderShareAmount()),
                row.getSettlementStatus(),
                row.getPayoutStatus(),
                row.getBatchReference(),
                row.getPeriodStart(),
                row.getPeriodEnd(),
                toLocalDateTime(row.getSettlementCreatedAt()),
                resolvePaidAt(
                        row.getSettlementPaidAt(),
                        row.getPayoutCompletedAt()
                ),
                row.getRemarks(),
                statementLink.objectKey(),
                statementLink.accessUrl()
        );
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record _PayoutSnapshot(
            LocalDateTime paidAt,
            BigDecimal amount
    ) {
    }
}
