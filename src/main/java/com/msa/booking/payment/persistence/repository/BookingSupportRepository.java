package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.PenaltyType;
import com.msa.booking.payment.domain.enums.SuspensionStatus;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

public interface BookingSupportRepository extends Repository<BookingEntity, Long> {
    interface ProviderPendingBookingRequestView {
        Long getRequestId();
        String getRequestCode();
        String getBookingType();
        String getProviderEntityType();
        Long getProviderEntityId();
        Long getCandidateId();
        String getCustomerName();
        String getCategoryLabel();
        String getLabourPricingModel();
        BigDecimal getQuotedPriceAmount();
        BigDecimal getDistanceKm();
        Timestamp getScheduledStartAt();
        Timestamp getExpiresAt();
    }

    interface ProviderActiveBookingView {
        Long getBookingId();
        String getBookingCode();
        String getBookingType();
        String getProviderEntityType();
        Long getProviderEntityId();
        String getBookingStatus();
        String getPaymentStatus();
        String getCustomerName();
        String getCustomerPhone();
        BigDecimal getQuotedPriceAmount();
        BigDecimal getPlatformFeeAmount();
        BigDecimal getDistanceKm();
        Timestamp getScheduledStartAt();
        Timestamp getCreatedAt();
        String getCategoryLabel();
        String getLabourPricingModel();
        String getAddressLabel();
        String getAddressLine1();
        String getAddressLine2();
        String getLandmark();
        String getCity();
        String getState();
        String getPostalCode();
        BigDecimal getProviderLatitude();
        BigDecimal getProviderLongitude();
        BigDecimal getLatitude();
        BigDecimal getLongitude();
        String getStartOtpCode();
        Timestamp getStartOtpExpiresAt();
        String getCompleteOtpCode();
        Timestamp getCompleteOtpExpiresAt();
        String getMutualCancelOtpCode();
        Timestamp getMutualCancelOtpExpiresAt();
    }

    interface ProviderBookingHistoryView {
        Long getBookingId();
        String getBookingCode();
        String getBookingType();
        String getProviderEntityType();
        Long getProviderEntityId();
        String getBookingStatus();
        String getPaymentStatus();
        String getCustomerName();
        String getCustomerPhone();
        BigDecimal getQuotedPriceAmount();
        BigDecimal getPlatformFeeAmount();
        BigDecimal getDistanceKm();
        Timestamp getScheduledStartAt();
        Timestamp getCreatedAt();
        String getCategoryLabel();
        String getLabourPricingModel();
    }

    interface ProviderLocationPhotoProjection {
        String getPhotoObjectKey();
        BigDecimal getLatitude();
        BigDecimal getLongitude();
    }

    @Query(value = """
            SELECT u.id AS userId, u.phone AS phone, up.full_name AS fullName
            FROM users u
            LEFT JOIN user_profiles up ON up.user_id = u.id
            WHERE u.id = :userId
            """, nativeQuery = true)
    Optional<BookingParticipantContactProjection> findUserContact(@Param("userId") Long userId);

    @Query(value = """
            SELECT u.id AS userId, u.phone AS phone, up.full_name AS fullName
            FROM labour_profiles lp
            JOIN users u ON u.id = lp.user_id
            LEFT JOIN user_profiles up ON up.user_id = u.id
            WHERE lp.id = :labourId
            """, nativeQuery = true)
    Optional<BookingParticipantContactProjection> findLabourContact(@Param("labourId") Long labourId);

    @Query(value = """
            SELECT u.id AS userId, u.phone AS phone, up.full_name AS fullName
            FROM service_providers sp
            JOIN users u ON u.id = sp.user_id
            LEFT JOIN user_profiles up ON up.user_id = u.id
            WHERE sp.id = :providerId
            """, nativeQuery = true)
    Optional<BookingParticipantContactProjection> findServiceProviderContact(@Param("providerId") Long providerId);

    @Query(value = "SELECT user_id FROM labour_profiles WHERE id = :labourId", nativeQuery = true)
    Optional<Long> findLabourUserId(@Param("labourId") Long labourId);

    @Query(value = "SELECT user_id FROM service_providers WHERE id = :providerId", nativeQuery = true)
    Optional<Long> findServiceProviderUserId(@Param("providerId") Long providerId);

    @Query(value = """
            SELECT COUNT(*)
            FROM reviews
            WHERE reviewer_user_id = :reviewerUserId
              AND booking_id = :bookingId
            """, nativeQuery = true)
    long countReviewsByReviewerAndBookingId(
            @Param("reviewerUserId") Long reviewerUserId,
            @Param("bookingId") Long bookingId
    );

    @Modifying
    @Query(value = """
            INSERT INTO reviews
                (reviewer_user_id, target_type, target_id, booking_id, rating, comment, review_status)
            VALUES
                (:reviewerUserId, :targetType, :targetId, :bookingId, :rating, NULLIF(:comment, ''), 'VISIBLE')
            """, nativeQuery = true)
    int insertBookingReview(
            @Param("reviewerUserId") Long reviewerUserId,
            @Param("targetType") String targetType,
            @Param("targetId") Long targetId,
            @Param("bookingId") Long bookingId,
            @Param("rating") int rating,
            @Param("comment") String comment
    );

    @Modifying
    @Query(value = """
            UPDATE labour_profiles lp
               SET lp.avg_rating = COALESCE((
                    SELECT ROUND(AVG(r.rating), 2)
                    FROM reviews r
                    WHERE r.target_type = 'LABOUR'
                      AND r.target_id = :labourId
                      AND r.review_status = 'VISIBLE'
               ), 0.00)
             WHERE lp.id = :labourId
            """, nativeQuery = true)
    int refreshLabourRating(@Param("labourId") Long labourId);

    @Modifying
    @Query(value = """
            UPDATE service_providers sp
               SET sp.avg_rating = COALESCE((
                        SELECT ROUND(AVG(r.rating), 2)
                        FROM reviews r
                        WHERE r.target_type = 'SERVICE_PROVIDER'
                          AND r.target_id = :providerId
                          AND r.review_status = 'VISIBLE'
                   ), 0.00),
                   sp.total_reviews = (
                        SELECT COUNT(*)
                        FROM reviews r
                        WHERE r.target_type = 'SERVICE_PROVIDER'
                          AND r.target_id = :providerId
                          AND r.review_status = 'VISIBLE'
                   )
             WHERE sp.id = :providerId
            """, nativeQuery = true)
    int refreshServiceProviderRating(@Param("providerId") Long providerId);

    @Query(value = """
            SELECT LOWER(pc.name)
            FROM booking_requests br
            JOIN provider_categories pc ON pc.id = br.category_id
            WHERE br.id = :bookingRequestId
            """, nativeQuery = true)
    Optional<String> findServiceCategoryNameByBookingRequestId(@Param("bookingRequestId") Long bookingRequestId);

    @Query(value = """
            SELECT COALESCE(psc.name, pc.name, lc.name, 'Booking')
            FROM booking_requests br
            LEFT JOIN labour_categories lc ON lc.id = br.category_id
            LEFT JOIN provider_categories pc ON pc.id = br.category_id
            LEFT JOIN provider_subcategories psc ON psc.id = br.subcategory_id
            WHERE br.id = :requestId
            """, nativeQuery = true)
    Optional<String> findBookingCategoryLabelByRequestId(@Param("requestId") Long requestId);

    @Query(value = """
            SELECT brc.distance_km
            FROM booking_request_candidates brc
            WHERE brc.request_id = :bookingRequestId
              AND brc.candidate_status = 'ACCEPTED'
              AND brc.provider_entity_type = :providerEntityType
              AND brc.provider_entity_id = :providerEntityId
            ORDER BY brc.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<BigDecimal> findAcceptedDistanceKmByBookingRequestId(
            @Param("bookingRequestId") Long bookingRequestId,
            @Param("providerEntityType") String providerEntityType,
            @Param("providerEntityId") Long providerEntityId
    );

    @Modifying
    @Query(value = """
            UPDATE service_providers
            SET available_service_men = available_service_men - 1
            WHERE id = :providerId AND available_service_men > 0
            """, nativeQuery = true)
    int decrementAvailableServiceMen(@Param("providerId") Long providerId);

    @Modifying
    @Query(value = """
            UPDATE service_providers
            SET available_service_men = available_service_men + 1
            WHERE id = :providerId
            """, nativeQuery = true)
    int incrementAvailableServiceMen(@Param("providerId") Long providerId);

    @Query(value = """
            SELECT DISTINCT
                br.id AS requestId,
                br.request_code AS requestCode,
                br.booking_type AS bookingType,
                brc.provider_entity_type AS providerEntityType,
                brc.provider_entity_id AS providerEntityId,
                brc.id AS candidateId,
                COALESCE(up.full_name, CONCAT('User ', u.id)) AS customerName,
                COALESCE(psc.name, pc.name, lc.name, 'Booking') AS categoryLabel,
                br.labour_pricing_model AS labourPricingModel,
                brc.quoted_price_amount AS quotedPriceAmount,
                brc.distance_km AS distanceKm,
                br.scheduled_start_at AS scheduledStartAt,
                br.expires_at AS expiresAt
            FROM booking_request_candidates brc
            INNER JOIN booking_requests br ON br.id = brc.request_id
            INNER JOIN users u ON u.id = br.user_id
            LEFT JOIN user_profiles up ON up.user_id = u.id
            LEFT JOIN labour_categories lc ON lc.id = br.category_id
            LEFT JOIN provider_categories pc ON pc.id = br.category_id
            LEFT JOIN provider_subcategories psc ON psc.id = br.subcategory_id
            WHERE brc.provider_entity_type = :providerEntityType
              AND brc.candidate_status = 'PENDING'
              AND br.request_status = 'OPEN'
              AND br.expires_at > CURRENT_TIMESTAMP
              AND brc.expires_at > CURRENT_TIMESTAMP
              AND (
                    (:providerEntityType = 'LABOUR' AND EXISTS (
                        SELECT 1
                        FROM labour_profiles lp
                        WHERE lp.id = brc.provider_entity_id
                          AND lp.user_id = :actingUserId
                    ))
                    OR
                    (:providerEntityType = 'SERVICE_PROVIDER' AND EXISTS (
                        SELECT 1
                        FROM service_providers sp
                        WHERE sp.id = brc.provider_entity_id
                          AND sp.user_id = :actingUserId
                    ))
              )
            ORDER BY br.created_at DESC, brc.id DESC
            """, nativeQuery = true)
    List<ProviderPendingBookingRequestView> findPendingBookingRequestsForProvider(
            @Param("actingUserId") Long actingUserId,
            @Param("providerEntityType") String providerEntityType
    );

    @Query(value = """
            SELECT
                b.id AS bookingId,
                b.booking_code AS bookingCode,
                b.booking_type AS bookingType,
                b.provider_entity_type AS providerEntityType,
                b.provider_entity_id AS providerEntityId,
                b.booking_status AS bookingStatus,
                b.payment_status AS paymentStatus,
                COALESCE(up.full_name, CONCAT('User ', u.id)) AS customerName,
                u.phone AS customerPhone,
                COALESCE(brc.quoted_price_amount, b.total_final_amount, b.total_estimated_amount, b.subtotal_amount, 0) AS quotedPriceAmount,
                COALESCE(b.platform_fee_amount, 0) AS platformFeeAmount,
                COALESCE(brc.distance_km, 0) AS distanceKm,
                b.scheduled_start_at AS scheduledStartAt,
                b.created_at AS createdAt,
                COALESCE(psc.name, pc.name, lc.name, 'Booking') AS categoryLabel,
                br.labour_pricing_model AS labourPricingModel,
                ua.label AS addressLabel,
                ua.address_line1 AS addressLine1,
                ua.address_line2 AS addressLine2,
                ua.landmark AS landmark,
                ua.city AS city,
                ua.state AS state,
                ua.postal_code AS postalCode,
                CASE
                    WHEN b.provider_entity_type = 'LABOUR' THEN (
                        SELECT lsa.center_latitude
                        FROM labour_service_areas lsa
                        WHERE lsa.labour_id = b.provider_entity_id
                        ORDER BY lsa.id DESC
                        LIMIT 1
                    )
                    ELSE (
                        SELECT psa.center_latitude
                        FROM provider_service_areas psa
                        WHERE psa.provider_id = b.provider_entity_id
                        ORDER BY psa.id DESC
                        LIMIT 1
                    )
                END AS providerLatitude,
                CASE
                    WHEN b.provider_entity_type = 'LABOUR' THEN (
                        SELECT lsa.center_longitude
                        FROM labour_service_areas lsa
                        WHERE lsa.labour_id = b.provider_entity_id
                        ORDER BY lsa.id DESC
                        LIMIT 1
                    )
                    ELSE (
                        SELECT psa.center_longitude
                        FROM provider_service_areas psa
                        WHERE psa.provider_id = b.provider_entity_id
                        ORDER BY psa.id DESC
                        LIMIT 1
                    )
                END AS providerLongitude,
                ua.latitude AS latitude,
                ua.longitude AS longitude,
                start_otp.otp_code AS startOtpCode,
                start_otp.expires_at AS startOtpExpiresAt,
                complete_otp.otp_code AS completeOtpCode,
                complete_otp.expires_at AS completeOtpExpiresAt,
                cancel_otp.otp_code AS mutualCancelOtpCode,
                cancel_otp.expires_at AS mutualCancelOtpExpiresAt
            FROM bookings b
            INNER JOIN users u ON u.id = b.user_id
            LEFT JOIN user_profiles up ON up.user_id = u.id
            LEFT JOIN booking_requests br ON br.id = b.booking_request_id
            LEFT JOIN booking_request_candidates brc
                   ON brc.request_id = br.id
                  AND brc.candidate_status = 'ACCEPTED'
                  AND brc.provider_entity_type = b.provider_entity_type
                  AND brc.provider_entity_id = b.provider_entity_id
            LEFT JOIN labour_categories lc ON lc.id = br.category_id
            LEFT JOIN provider_categories pc ON pc.id = br.category_id
            LEFT JOIN provider_subcategories psc ON psc.id = br.subcategory_id
            LEFT JOIN user_addresses ua ON ua.id = b.address_id
            LEFT JOIN booking_action_otps start_otp
                   ON start_otp.id = (
                        SELECT bao.id
                        FROM booking_action_otps bao
                        WHERE bao.booking_id = b.id
                          AND bao.otp_purpose = 'START_WORK'
                          AND bao.otp_status = 'GENERATED'
                        ORDER BY bao.id DESC
                        LIMIT 1
                   )
            LEFT JOIN booking_action_otps complete_otp
                   ON complete_otp.id = (
                        SELECT bao.id
                        FROM booking_action_otps bao
                        WHERE bao.booking_id = b.id
                          AND bao.otp_purpose = 'COMPLETE_WORK'
                          AND bao.otp_status = 'GENERATED'
                        ORDER BY bao.id DESC
                        LIMIT 1
                   )
            LEFT JOIN booking_action_otps cancel_otp
                   ON cancel_otp.id = (
                        SELECT bao.id
                        FROM booking_action_otps bao
                        WHERE bao.booking_id = b.id
                          AND bao.otp_purpose = 'MUTUAL_CANCEL'
                          AND bao.otp_status = 'GENERATED'
                        ORDER BY bao.id DESC
                        LIMIT 1
                   )
            WHERE b.provider_entity_type = :providerEntityType
              AND b.booking_status IN ('PAYMENT_PENDING', 'PAYMENT_COMPLETED', 'ARRIVED', 'IN_PROGRESS')
              AND (
                    (:providerEntityType = 'LABOUR' AND EXISTS (
                        SELECT 1
                        FROM labour_profiles lp
                        WHERE lp.id = b.provider_entity_id
                          AND lp.user_id = :actingUserId
                    ))
                    OR
                    (:providerEntityType = 'SERVICE_PROVIDER' AND EXISTS (
                        SELECT 1
                        FROM service_providers sp
                        WHERE sp.id = b.provider_entity_id
                          AND sp.user_id = :actingUserId
                    ))
              )
            ORDER BY
                CASE b.booking_status
                    WHEN 'IN_PROGRESS' THEN 1
                    WHEN 'ARRIVED' THEN 2
                    WHEN 'PAYMENT_COMPLETED' THEN 3
                    WHEN 'PAYMENT_PENDING' THEN 4
                    ELSE 9
                END,
                b.created_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ProviderActiveBookingView> findLatestActiveBookingForProvider(
            @Param("actingUserId") Long actingUserId,
            @Param("providerEntityType") String providerEntityType
    );

    @Query(value = """
            SELECT
                b.id AS bookingId,
                b.booking_code AS bookingCode,
                b.booking_type AS bookingType,
                b.provider_entity_type AS providerEntityType,
                b.provider_entity_id AS providerEntityId,
                b.booking_status AS bookingStatus,
                b.payment_status AS paymentStatus,
                COALESCE(up.full_name, CONCAT('User ', u.id)) AS customerName,
                u.phone AS customerPhone,
                COALESCE(brc.quoted_price_amount, b.total_final_amount, b.total_estimated_amount, b.subtotal_amount, 0) AS quotedPriceAmount,
                COALESCE(b.platform_fee_amount, 0) AS platformFeeAmount,
                COALESCE(brc.distance_km, 0) AS distanceKm,
                b.scheduled_start_at AS scheduledStartAt,
                b.created_at AS createdAt,
                COALESCE(psc.name, pc.name, lc.name, 'Booking') AS categoryLabel,
                br.labour_pricing_model AS labourPricingModel
            FROM bookings b
            INNER JOIN users u ON u.id = b.user_id
            LEFT JOIN user_profiles up ON up.user_id = u.id
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
            WHERE b.provider_entity_type = :providerEntityType
              AND (
                    (:providerEntityType = 'LABOUR' AND EXISTS (
                        SELECT 1
                        FROM labour_profiles lp
                        WHERE lp.id = b.provider_entity_id
                          AND lp.user_id = :actingUserId
                    ))
                    OR
                    (:providerEntityType = 'SERVICE_PROVIDER' AND EXISTS (
                        SELECT 1
                        FROM service_providers sp
                        WHERE sp.id = b.provider_entity_id
                          AND sp.user_id = :actingUserId
                    ))
              )
            ORDER BY b.created_at DESC
            LIMIT 50
            """, nativeQuery = true)
    List<ProviderBookingHistoryView> findProviderBookingHistory(
            @Param("actingUserId") Long actingUserId,
            @Param("providerEntityType") String providerEntityType
    );

    @Query(value = """
            SELECT f.object_key AS photoObjectKey,
                   lsa.center_latitude AS latitude,
                   lsa.center_longitude AS longitude
            FROM labour_profiles lp
            LEFT JOIN user_profiles up ON up.user_id = lp.user_id
            LEFT JOIN files f ON f.id = up.photo_file_id
            LEFT JOIN labour_service_areas lsa ON lsa.labour_id = lp.id
            WHERE lp.id = :providerEntityId
            ORDER BY lsa.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ProviderLocationPhotoProjection> findLabourLocationPhoto(@Param("providerEntityId") Long providerEntityId);

    @Query(value = """
            SELECT f.object_key AS photoObjectKey,
                   psa.center_latitude AS latitude,
                   psa.center_longitude AS longitude
            FROM service_providers sp
            LEFT JOIN user_profiles up ON up.user_id = sp.user_id
            LEFT JOIN files f ON f.id = up.photo_file_id
            LEFT JOIN provider_service_areas psa ON psa.provider_id = sp.id
            WHERE sp.id = :providerEntityId
            ORDER BY psa.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ProviderLocationPhotoProjection> findServiceProviderLocationPhoto(@Param("providerEntityId") Long providerEntityId);

    @Query("""
            select count(p)
            from PenaltyEntity p
            where p.penalizedUserId = :userId
              and p.penaltyType = :penaltyType
              and lower(p.reason) like lower(concat(:reasonPrefix, '%'))
              and p.appliedAt >= :from
            """)
    long countPenaltiesSince(
            @Param("userId") Long userId,
            @Param("penaltyType") PenaltyType penaltyType,
            @Param("reasonPrefix") String reasonPrefix,
            @Param("from") LocalDateTime from
    );

    @Query("""
            select count(s)
            from SuspensionEntity s
            where s.userId = :userId
              and s.status = :status
              and (s.endAt is null or s.endAt >= :now)
            """)
    long countActiveSuspensions(
            @Param("userId") Long userId,
            @Param("status") SuspensionStatus status,
            @Param("now") LocalDateTime now
    );
}
