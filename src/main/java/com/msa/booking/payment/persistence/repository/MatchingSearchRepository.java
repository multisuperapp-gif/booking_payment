package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface MatchingSearchRepository extends Repository<BookingRequestEntity, Long> {
    @Query(value = """
            SELECT
              lp.id AS providerEntityId,
              CASE
                WHEN :pricingModel = 'HALF_DAY' THEN COALESCE(MAX(CASE WHEN pr.pricing_model = 'HALF_DAY' AND pr.is_enabled = 1 THEN pr.half_day_price END), 0.00)
                WHEN :pricingModel = 'FULL_DAY' THEN COALESCE(MAX(CASE WHEN pr.pricing_model = 'FULL_DAY' AND pr.is_enabled = 1 THEN pr.full_day_price END), 0.00)
                ELSE COALESCE(MAX(CASE WHEN pr.pricing_model = 'HOURLY' AND pr.is_enabled = 1 THEN pr.hourly_price END), 0.00)
              END AS quotedPriceAmount,
              MAX(COALESCE(lsa.radius_km, 0)) AS radiusKm,
              MIN(
                6371 * ACOS(
                  LEAST(1, GREATEST(-1,
                    COS(RADIANS(:latitude)) * COS(RADIANS(lsa.center_latitude)) *
                    COS(RADIANS(lsa.center_longitude) - RADIANS(:longitude)) +
                    SIN(RADIANS(:latitude)) * SIN(RADIANS(lsa.center_latitude))
                  ))
                )
              ) AS distanceKm
            FROM labour_profiles lp
            JOIN labour_service_areas lsa ON lsa.labour_id = lp.id
            JOIN labour_pricing pr ON pr.labour_id = lp.id
              AND pr.category_id = :categoryId
              AND pr.is_enabled = 1
            WHERE lp.approval_status = 'APPROVED'
              AND lp.online_status = 1
              AND EXISTS (
                SELECT 1
                FROM labour_skills ls
                WHERE ls.labour_id = lp.id
                  AND ls.category_id = :categoryId
              )
              AND NOT EXISTS (
                SELECT 1
                FROM bookings b
                WHERE b.provider_entity_type = 'LABOUR'
                  AND b.provider_entity_id = lp.id
                  AND b.booking_status IN ('ACCEPTED','PAYMENT_PENDING','PAYMENT_COMPLETED','ARRIVED','IN_PROGRESS')
              )
            GROUP BY lp.id
            HAVING quotedPriceAmount > 0
               AND (:priceMin IS NULL OR quotedPriceAmount >= :priceMin)
               AND (:priceMax IS NULL OR quotedPriceAmount <= :priceMax)
               AND (
                    distanceKm IS NULL
                    OR radiusKm <= 0
                    OR distanceKm <= radiusKm
               )
            ORDER BY distanceKm ASC, quotedPriceAmount ASC
            """, nativeQuery = true)
    List<LabourCandidateProjection> findEligibleLabourCandidates(
            @Param("categoryId") Long categoryId,
            @Param("pricingModel") String pricingModel,
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude,
            @Param("priceMin") BigDecimal priceMin,
            @Param("priceMax") BigDecimal priceMax
    );

    @Query(value = """
            SELECT
              sp.id AS providerEntityId,
              MIN(prr.visiting_charge) AS quotedPriceAmount,
              MIN(
                6371 * ACOS(
                  LEAST(1, GREATEST(-1,
                    COS(RADIANS(:latitude)) * COS(RADIANS(psa.center_latitude)) *
                    COS(RADIANS(psa.center_longitude) - RADIANS(:longitude)) +
                    SIN(RADIANS(:latitude)) * SIN(RADIANS(psa.center_latitude))
                  ))
                )
              ) AS distanceKm
            FROM service_providers sp
            JOIN provider_service_areas psa ON psa.provider_id = sp.id
            JOIN provider_services ps ON ps.provider_id = sp.id
              AND ps.is_active = 1
            JOIN provider_subcategories psc ON psc.id = ps.subcategory_id
            JOIN provider_pricing_rules prr ON prr.provider_service_id = ps.id
              AND prr.visiting_charge > 0
            WHERE sp.approval_status = 'APPROVED'
              AND sp.online_status = 1
              AND sp.available_service_men > 0
              AND (:subcategoryId IS NULL OR ps.subcategory_id = :subcategoryId)
              AND (:categoryId IS NULL OR psc.category_id = :categoryId)
              AND (
                6371 * ACOS(
                  LEAST(1, GREATEST(-1,
                    COS(RADIANS(:latitude)) * COS(RADIANS(psa.center_latitude)) *
                    COS(RADIANS(psa.center_longitude) - RADIANS(:longitude)) +
                    SIN(RADIANS(:latitude)) * SIN(RADIANS(psa.center_latitude))
                  ))
                )
              ) <= psa.radius_km
            GROUP BY sp.id
            HAVING (:priceMin IS NULL OR quotedPriceAmount >= :priceMin)
               AND (:priceMax IS NULL OR quotedPriceAmount <= :priceMax)
            ORDER BY distanceKm ASC, quotedPriceAmount ASC
            """, nativeQuery = true)
    List<ServiceCandidateProjection> findEligibleServiceCandidates(
            @Param("categoryId") Long categoryId,
            @Param("subcategoryId") Long subcategoryId,
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude,
            @Param("priceMin") BigDecimal priceMin,
            @Param("priceMax") BigDecimal priceMax
    );
}
