package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.PenaltyType;
import com.msa.booking.payment.domain.enums.SuspensionStatus;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface BookingSupportRepository extends Repository<BookingEntity, Long> {
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
