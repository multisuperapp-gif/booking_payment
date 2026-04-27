package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.SettlementLineItemEntity;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementLineItemRepository extends JpaRepository<SettlementLineItemEntity, Long> {
    interface ProviderSettlementRecordView {
        Long getSettlementId();
        String getSettlementCode();
        String getSettlementStatus();
        Timestamp getSettlementCreatedAt();
        Timestamp getSettlementPaidAt();
        LocalDate getPeriodStart();
        LocalDate getPeriodEnd();
        BigDecimal getProviderShareAmount();
        String getRemarks();
        Long getBookingId();
        String getBookingCode();
        String getBookingStatus();
        String getPaymentStatus();
        BigDecimal getQuotedPriceAmount();
        BigDecimal getPlatformFeeAmount();
        String getCategoryLabel();
        String getLabourPricingModel();
        String getPayoutStatus();
        String getBatchReference();
        Timestamp getPayoutCompletedAt();
    }

    boolean existsBySourceTypeAndSourceIdAndLineType(String sourceType, Long sourceId, String lineType);

    Optional<SettlementLineItemEntity> findTopBySourceTypeAndSourceIdAndLineTypeOrderByIdAsc(
            String sourceType,
            Long sourceId,
            String lineType
    );

    @Query(value = """
            SELECT
                s.id AS settlementId,
                s.settlement_code AS settlementCode,
                s.status AS settlementStatus,
                s.created_at AS settlementCreatedAt,
                s.paid_at AS settlementPaidAt,
                sc.period_start AS periodStart,
                sc.period_end AS periodEnd,
                sli.amount AS providerShareAmount,
                sli.remarks AS remarks,
                b.id AS bookingId,
                b.booking_code AS bookingCode,
                b.booking_status AS bookingStatus,
                b.payment_status AS paymentStatus,
                COALESCE(brc.quoted_price_amount, b.total_final_amount, b.total_estimated_amount, b.subtotal_amount, 0) AS quotedPriceAmount,
                COALESCE(b.platform_fee_amount, 0) AS platformFeeAmount,
                COALESCE(psc.name, pc.name, lc.name, 'Booking') AS categoryLabel,
                br.labour_pricing_model AS labourPricingModel,
                pbi.payout_status AS payoutStatus,
                pb.batch_reference AS batchReference,
                pb.completed_at AS payoutCompletedAt
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
            """, nativeQuery = true)
    List<ProviderSettlementRecordView> findProviderSettlementRecords(
            @Param("beneficiaryType") String beneficiaryType,
            @Param("providerEntityType") String providerEntityType,
            @Param("actingUserId") Long actingUserId
    );
}
