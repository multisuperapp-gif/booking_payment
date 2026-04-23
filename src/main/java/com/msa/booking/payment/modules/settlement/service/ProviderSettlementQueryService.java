package com.msa.booking.payment.modules.settlement.service;

import static com.msa.booking.payment.modules.settlement.dto.ProviderSettlementDtos.ProviderSettlementDashboardData;
import static com.msa.booking.payment.modules.settlement.dto.ProviderSettlementDtos.ProviderSettlementRecordData;

import com.msa.booking.payment.domain.enums.ProviderEntityType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProviderSettlementQueryService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ProviderSettlementQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ProviderSettlementDashboardData weeklyDashboard(Long actingUserId, ProviderEntityType providerEntityType) {
        List<ProviderSettlementRecordData> records = jdbcTemplate.query("""
                SELECT
                    s.id AS settlement_id,
                    s.settlement_code,
                    s.status AS settlement_status,
                    s.created_at AS settlement_created_at,
                    s.paid_at AS settlement_paid_at,
                    sc.period_start,
                    sc.period_end,
                    sli.amount AS provider_share_amount,
                    sli.remarks,
                    b.id AS booking_id,
                    b.booking_code,
                    b.booking_status,
                    b.payment_status,
                    COALESCE(brc.quoted_price_amount, b.total_final_amount, b.total_estimated_amount, b.subtotal_amount, 0) AS quoted_price_amount,
                    COALESCE(b.platform_fee_amount, 0) AS platform_fee_amount,
                    COALESCE(psc.name, pc.name, lc.name, 'Booking') AS category_label,
                    br.labour_pricing_model,
                    pbi.payout_status,
                    pb.batch_reference,
                    pb.completed_at AS payout_completed_at
                FROM settlement_line_items sli
                INNER JOIN settlements s ON s.id = sli.settlement_id
                INNER JOIN settlement_cycles sc ON sc.id = s.settlement_cycle_id
                INNER JOIN bookings b
                        ON b.id = sli.source_id
                       AND sli.source_type = 'BOOKING'
                LEFT JOIN booking_requests br ON br.id = b.booking_request_id
                LEFT JOIN booking_request_candidates brc
                       ON brc.id = (
                            SELECT brc2.id
                            FROM booking_request_candidates brc2
                            WHERE brc2.request_id = br.id
                              AND brc2.provider_entity_type = b.provider_entity_type
                              AND brc2.provider_entity_id = b.provider_entity_id
                            ORDER BY
                                CASE brc2.candidate_status
                                    WHEN 'ACCEPTED' THEN 0
                                    WHEN 'CONVERTED_TO_BOOKING' THEN 1
                                    ELSE 9
                                END,
                                brc2.id DESC
                            LIMIT 1
                       )
                LEFT JOIN labour_categories lc ON lc.id = br.category_id
                LEFT JOIN provider_categories pc ON pc.id = br.category_id
                LEFT JOIN provider_subcategories psc ON psc.id = br.subcategory_id
                LEFT JOIN payout_batch_items pbi
                       ON pbi.id = (
                            SELECT pbi2.id
                            FROM payout_batch_items pbi2
                            WHERE pbi2.settlement_id = s.id
                            ORDER BY pbi2.id DESC
                            LIMIT 1
                       )
                LEFT JOIN payout_batches pb ON pb.id = pbi.payout_batch_id
                WHERE sli.line_type = 'ADJUSTMENT'
                  AND s.beneficiary_type = :beneficiaryType
                  AND (
                        (:providerEntityType = 'LABOUR' AND EXISTS (
                            SELECT 1
                            FROM labour_profiles lp
                            WHERE lp.id = s.beneficiary_id
                              AND lp.user_id = :actingUserId
                        ))
                        OR
                        (:providerEntityType = 'SERVICE_PROVIDER' AND EXISTS (
                            SELECT 1
                            FROM service_providers sp
                            WHERE sp.id = s.beneficiary_id
                              AND sp.user_id = :actingUserId
                        ))
                  )
                ORDER BY COALESCE(pb.completed_at, s.paid_at, s.created_at) DESC, s.id DESC, b.id DESC
                """, new MapSqlParameterSource()
                .addValue("beneficiaryType", providerEntityType == ProviderEntityType.LABOUR ? "LABOUR" : "PROVIDER")
                .addValue("providerEntityType", providerEntityType.name())
                .addValue("actingUserId", actingUserId), (rs, rowNum) -> new ProviderSettlementRecordData(
                rs.getLong("settlement_id"),
                rs.getString("settlement_code"),
                rs.getLong("booking_id"),
                rs.getString("booking_code"),
                rs.getString("booking_status"),
                rs.getString("payment_status"),
                rs.getString("category_label"),
                rs.getString("labour_pricing_model"),
                money(rs.getBigDecimal("quoted_price_amount")),
                money(rs.getBigDecimal("platform_fee_amount")),
                money(rs.getBigDecimal("provider_share_amount")),
                rs.getString("settlement_status"),
                rs.getString("payout_status"),
                rs.getString("batch_reference"),
                rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(),
                toLocalDateTime(rs.getTimestamp("settlement_created_at")),
                resolvePaidAt(
                        rs.getTimestamp("settlement_paid_at"),
                        rs.getTimestamp("payout_completed_at")
                ),
                rs.getString("remarks")
        ));

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

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record _PayoutSnapshot(
            LocalDateTime paidAt,
            BigDecimal amount
    ) {
    }
}
