package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.ProviderActiveBookingData;
import com.msa.booking.payment.booking.dto.ProviderPendingBookingRequestData;
import com.msa.booking.payment.booking.dto.UserBookingRequestStatusData;
import com.msa.booking.payment.booking.service.BookingRequestQueryService;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.BookingRequestCandidateStatus;
import com.msa.booking.payment.domain.enums.BookingRequestStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestCandidateEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import com.msa.booking.payment.persistence.repository.BookingParticipantContactProjection;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestCandidateRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BookingRequestQueryServiceImpl implements BookingRequestQueryService {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final BookingSupportRepository bookingSupportRepository;
    private final BookingRequestRepository bookingRequestRepository;
    private final BookingRequestCandidateRepository bookingRequestCandidateRepository;
    private final BookingRepository bookingRepository;
    private final BookingPolicyService bookingPolicyService;

    public BookingRequestQueryServiceImpl(
            NamedParameterJdbcTemplate jdbcTemplate,
            BookingSupportRepository bookingSupportRepository,
            BookingRequestRepository bookingRequestRepository,
            BookingRequestCandidateRepository bookingRequestCandidateRepository,
            BookingRepository bookingRepository,
            BookingPolicyService bookingPolicyService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookingSupportRepository = bookingSupportRepository;
        this.bookingRequestRepository = bookingRequestRepository;
        this.bookingRequestCandidateRepository = bookingRequestCandidateRepository;
        this.bookingRepository = bookingRepository;
        this.bookingPolicyService = bookingPolicyService;
    }

    @Override
    public List<ProviderPendingBookingRequestData> pendingForProvider(
            Long actingUserId,
            ProviderEntityType providerEntityType
    ) {
        return jdbcTemplate.query("""
                SELECT
                    br.id AS request_id,
                    br.request_code,
                    br.booking_type,
                    brc.provider_entity_type,
                    brc.provider_entity_id,
                    brc.id AS candidate_id,
                    COALESCE(up.full_name, CONCAT('User ', u.id)) AS customer_name,
                    COALESCE(psc.name, pc.name, lc.name, 'Booking') AS category_label,
                    br.labour_pricing_model,
                    brc.quoted_price_amount,
                    brc.distance_km,
                    br.scheduled_start_at,
                    br.expires_at
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
                """, new MapSqlParameterSource()
                .addValue("providerEntityType", providerEntityType.name())
                .addValue("actingUserId", actingUserId), (rs, rowNum) -> new ProviderPendingBookingRequestData(
                rs.getLong("request_id"),
                rs.getString("request_code"),
                BookingFlowType.valueOf(rs.getString("booking_type")),
                ProviderEntityType.valueOf(rs.getString("provider_entity_type")),
                rs.getLong("provider_entity_id"),
                rs.getLong("candidate_id"),
                rs.getString("customer_name"),
                rs.getString("category_label"),
                rs.getString("labour_pricing_model"),
                rs.getBigDecimal("quoted_price_amount"),
                rs.getBigDecimal("distance_km"),
                rs.getTimestamp("scheduled_start_at").toLocalDateTime(),
                rs.getTimestamp("expires_at").toLocalDateTime()
        ));
    }

    @Override
    public ProviderActiveBookingData latestActiveForProvider(Long actingUserId, ProviderEntityType providerEntityType) {
        ProviderActiveBookingData raw = jdbcTemplate.query("""
                SELECT
                    b.id AS booking_id,
                    b.booking_code,
                    b.booking_type,
                    b.provider_entity_type,
                    b.provider_entity_id,
                    b.booking_status,
                    b.payment_status,
                    COALESCE(up.full_name, CONCAT('User ', u.id)) AS customer_name,
                    u.phone AS customer_phone,
                    COALESCE(brc.quoted_price_amount, b.total_final_amount, b.total_estimated_amount, b.subtotal_amount, 0) AS quoted_price_amount,
                    COALESCE(brc.distance_km, 0) AS distance_km,
                    b.scheduled_start_at,
                    b.created_at,
                    COALESCE(psc.name, pc.name, lc.name, 'Booking') AS category_label,
                    br.labour_pricing_model,
                    ua.label AS address_label,
                    ua.address_line1,
                    ua.address_line2,
                    ua.landmark,
                    ua.city,
                    ua.state,
                    ua.postal_code,
                    ua.latitude,
                    ua.longitude,
                    start_otp.otp_code AS start_otp_code,
                    start_otp.expires_at AS start_otp_expires_at,
                    complete_otp.otp_code AS complete_otp_code,
                    complete_otp.expires_at AS complete_otp_expires_at,
                    cancel_otp.otp_code AS mutual_cancel_otp_code,
                    cancel_otp.expires_at AS mutual_cancel_otp_expires_at
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
                """, new MapSqlParameterSource()
                .addValue("providerEntityType", providerEntityType.name())
                .addValue("actingUserId", actingUserId), rs -> {
            if (!rs.next()) {
                return null;
            }
            LocalDateTime scheduledStartAt = rs.getTimestamp("scheduled_start_at").toLocalDateTime();
            LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
            BookingFlowType bookingType = BookingFlowType.valueOf(rs.getString("booking_type"));
            String categoryLabel = rs.getString("category_label");
            return new ProviderActiveBookingData(
                    rs.getLong("booking_id"),
                    rs.getString("booking_code"),
                    bookingType,
                    ProviderEntityType.valueOf(rs.getString("provider_entity_type")),
                    rs.getLong("provider_entity_id"),
                    BookingLifecycleStatus.valueOf(rs.getString("booking_status")),
                    PayablePaymentStatus.valueOf(rs.getString("payment_status")),
                    rs.getString("customer_name"),
                    rs.getString("customer_phone"),
                    rs.getBigDecimal("quoted_price_amount"),
                    rs.getBigDecimal("distance_km"),
                    scheduledStartAt,
                    createdAt.plusSeconds(bookingPolicyService.acceptedPaymentTimeoutSeconds()),
                    scheduledStartAt.plusMinutes(resolveReachTimelineMinutes(bookingType, categoryLabel)),
                    categoryLabel,
                    rs.getString("labour_pricing_model"),
                    rs.getString("address_label"),
                    rs.getString("address_line1"),
                    rs.getString("address_line2"),
                    rs.getString("landmark"),
                    rs.getString("city"),
                    rs.getString("state"),
                    rs.getString("postal_code"),
                    toBigDecimal(rs.getObject("latitude")),
                    toBigDecimal(rs.getObject("longitude")),
                    rs.getString("start_otp_code"),
                    toLocalDateTime(rs.getTimestamp("start_otp_expires_at")),
                    rs.getString("complete_otp_code"),
                    toLocalDateTime(rs.getTimestamp("complete_otp_expires_at")),
                    rs.getString("mutual_cancel_otp_code"),
                    toLocalDateTime(rs.getTimestamp("mutual_cancel_otp_expires_at"))
            );
        });
        if (raw == null) {
            return null;
        }

        String resolvedPhone = raw.customerPhone();
        boolean revealPhone = raw.paymentStatus() == PayablePaymentStatus.PAID
                || raw.bookingStatus() == BookingLifecycleStatus.ARRIVED
                || raw.bookingStatus() == BookingLifecycleStatus.IN_PROGRESS;
        if (!revealPhone) {
            resolvedPhone = maskPhone(resolvedPhone);
        }
        return new ProviderActiveBookingData(
                raw.bookingId(),
                raw.bookingCode(),
                raw.bookingType(),
                raw.providerEntityType(),
                raw.providerEntityId(),
                raw.bookingStatus(),
                raw.paymentStatus(),
                raw.customerName(),
                resolvedPhone,
                raw.quotedPriceAmount(),
                raw.distanceKm(),
                raw.scheduledStartAt(),
                raw.paymentDueAt(),
                raw.reachByAt(),
                raw.categoryLabel(),
                raw.labourPricingModel(),
                raw.addressLabel(),
                raw.addressLine1(),
                raw.addressLine2(),
                raw.landmark(),
                raw.city(),
                raw.state(),
                raw.postalCode(),
                raw.destinationLatitude(),
                raw.destinationLongitude(),
                raw.startOtpCode(),
                raw.startOtpExpiresAt(),
                raw.completeOtpCode(),
                raw.completeOtpExpiresAt(),
                raw.mutualCancelOtpCode(),
                raw.mutualCancelOtpExpiresAt()
        );
    }

    @Override
    public UserBookingRequestStatusData statusForUser(Long actingUserId, Long requestId) {
        BookingRequestEntity request = bookingRequestRepository.findById(requestId)
                .orElseThrow(() -> new BadRequestException("Booking request not found."));
        if (!actingUserId.equals(request.getUserId())) {
            throw new BadRequestException("Authenticated user cannot access this booking request.");
        }
        BookingEntity booking = bookingRepository.findByBookingRequestIdOrderByIdAsc(requestId)
                .stream()
                .findFirst()
                .orElse(null);
        return buildStatusData(request, booking);
    }

    @Override
    public UserBookingRequestStatusData latestActiveForUser(Long actingUserId) {
        for (BookingRequestEntity request : bookingRequestRepository.findTop20ByUserIdOrderByCreatedAtDesc(actingUserId)) {
            BookingEntity booking = bookingRepository.findByBookingRequestIdOrderByIdAsc(request.getId())
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (isActiveForUser(request, booking)) {
                return buildStatusData(request, booking);
            }
        }
        return null;
    }

    private boolean isActiveForUser(BookingRequestEntity request, BookingEntity booking) {
        if (request.getRequestStatus() == BookingRequestStatus.OPEN || request.getRequestStatus() == BookingRequestStatus.ACCEPTED) {
            return true;
        }
        if (booking == null || booking.getBookingStatus() == null) {
            return false;
        }
        return booking.getBookingStatus() != BookingLifecycleStatus.COMPLETED
                && booking.getBookingStatus() != BookingLifecycleStatus.CANCELLED;
    }

    private UserBookingRequestStatusData buildStatusData(BookingRequestEntity request, BookingEntity booking) {
        BookingRequestCandidateEntity acceptedCandidate = bookingRequestCandidateRepository
                .findByRequestIdAndCandidateStatus(request.getId(), BookingRequestCandidateStatus.ACCEPTED)
                .stream()
                .findFirst()
                .orElse(null);
        int acceptedProviderCount = bookingRequestCandidateRepository
                .findByRequestIdAndCandidateStatus(request.getId(), BookingRequestCandidateStatus.ACCEPTED)
                .size();
        int pendingProviderCount = bookingRequestCandidateRepository
                .findByRequestIdAndCandidateStatus(request.getId(), BookingRequestCandidateStatus.PENDING)
                .size();
        List<BookingEntity> requestBookings = bookingRepository.findByBookingRequestIdOrderByIdAsc(request.getId());
        BookingEntity paymentReadyBooking = requestBookings.stream()
                .filter(candidateBooking -> candidateBooking.getBookingStatus() == BookingLifecycleStatus.PAYMENT_PENDING)
                .filter(candidateBooking -> candidateBooking.getPaymentStatus() == PayablePaymentStatus.UNPAID
                        || candidateBooking.getPaymentStatus() == PayablePaymentStatus.PENDING)
                .findFirst()
                .orElse(null);
        if (paymentReadyBooking != null) {
            booking = paymentReadyBooking;
        } else if (booking == null && !requestBookings.isEmpty()) {
            booking = requestBookings.getFirst();
        }

        Long candidateId = acceptedCandidate == null ? null : acceptedCandidate.getId();
        ProviderEntityType providerEntityType = acceptedCandidate == null ? null : acceptedCandidate.getProviderEntityType();
        Long providerEntityId = acceptedCandidate == null ? null : acceptedCandidate.getProviderEntityId();
        String providerName = null;
        String providerPhone = null;
        ProviderLocationPhotoData providerLocationPhotoData = null;
        java.math.BigDecimal quotedPriceAmount = acceptedCandidate == null ? null : acceptedCandidate.getQuotedPriceAmount();
        java.math.BigDecimal totalAcceptedQuotedPriceAmount = requestBookings.stream()
                .filter(candidateBooking -> candidateBooking.getBookingStatus() != BookingLifecycleStatus.CANCELLED)
                .map(candidateBooking -> candidateBooking.getSubtotalAmount() == null ? BigDecimal.ZERO : candidateBooking.getSubtotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        java.math.BigDecimal totalAcceptedBookingChargeAmount = request.getBookingType() == BookingFlowType.LABOUR
                ? requestBookings.stream()
                        .filter(candidateBooking -> candidateBooking.getBookingStatus() != BookingLifecycleStatus.CANCELLED)
                        .map(candidateBooking -> bookingPolicyService.labourBookingChargeAmount(candidateBooking.getSubtotalAmount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : BigDecimal.ZERO;
        java.math.BigDecimal distanceKm = acceptedCandidate == null ? null : acceptedCandidate.getDistanceKm();

        if (acceptedCandidate != null) {
            BookingParticipantContactProjection contact = acceptedCandidate.getProviderEntityType() == ProviderEntityType.LABOUR
                    ? bookingSupportRepository.findLabourContact(acceptedCandidate.getProviderEntityId()).orElse(null)
                    : bookingSupportRepository.findServiceProviderContact(acceptedCandidate.getProviderEntityId()).orElse(null);
            if (contact != null) {
                providerName = contact.getFullName();
                boolean revealPhone = booking != null && booking.getPaymentStatus() == PayablePaymentStatus.PAID;
                providerPhone = revealPhone ? contact.getPhone() : maskPhone(contact.getPhone());
            }
            providerLocationPhotoData = providerLocationPhoto(
                    acceptedCandidate.getProviderEntityType(),
                    acceptedCandidate.getProviderEntityId()
            );
        }

        LocalDateTime paymentDueAt = booking == null || booking.getCreatedAt() == null
                ? null
                : booking.getCreatedAt().plusSeconds(bookingPolicyService.acceptedPaymentTimeoutSeconds());
        String categoryLabel = resolveCategoryLabel(request);
        LocalDateTime reachByAt = booking == null || booking.getScheduledStartAt() == null
                ? null
                : booking.getScheduledStartAt().plusMinutes(resolveReachTimelineMinutes(request.getBookingType(), categoryLabel));
        boolean revealProviderLiveLocation = booking != null && booking.getPaymentStatus() == PayablePaymentStatus.PAID;

        return new UserBookingRequestStatusData(
                request.getId(),
                request.getRequestCode(),
                request.getBookingType(),
                request.getRequestStatus(),
                candidateId,
                providerEntityType,
                providerEntityId,
                providerName,
                providerPhone,
                quotedPriceAmount,
                totalAcceptedQuotedPriceAmount,
                totalAcceptedBookingChargeAmount,
                distanceKm,
                providerLocationPhotoData == null ? null : providerLocationPhotoData.photoObjectKey(),
                !revealProviderLiveLocation || providerLocationPhotoData == null ? null : providerLocationPhotoData.latitude(),
                !revealProviderLiveLocation || providerLocationPhotoData == null ? null : providerLocationPhotoData.longitude(),
                paymentDueAt,
                reachByAt,
                request.getLabourPricingModel(),
                request.getRequestedProviderCount() == null ? 1 : request.getRequestedProviderCount(),
                acceptedProviderCount,
                pendingProviderCount,
                booking == null ? null : booking.getId(),
                booking == null ? null : booking.getBookingCode(),
                booking == null ? null : booking.getBookingStatus(),
                booking == null ? null : booking.getPaymentStatus()
        );
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        return "X".repeat(Math.max(trimmed.length() - 4, 0)) + trimmed.substring(trimmed.length() - 4);
    }

    private int resolveReachTimelineMinutes(BookingFlowType bookingType, String categoryLabel) {
        if (bookingType == BookingFlowType.LABOUR) {
            return bookingPolicyService.labourReachTimelineMinutes();
        }
        return "automobile".equalsIgnoreCase(categoryLabel)
                ? bookingPolicyService.serviceAutomobileReachTimelineMinutes()
                : bookingPolicyService.serviceDefaultReachTimelineMinutes();
    }

    private String resolveCategoryLabel(BookingRequestEntity request) {
        return jdbcTemplate.query("""
                SELECT COALESCE(psc.name, pc.name, lc.name, 'Booking') AS category_label
                FROM booking_requests br
                LEFT JOIN labour_categories lc ON lc.id = br.category_id
                LEFT JOIN provider_categories pc ON pc.id = br.category_id
                LEFT JOIN provider_subcategories psc ON psc.id = br.subcategory_id
                WHERE br.id = :requestId
                """, new MapSqlParameterSource("requestId", request.getId()), rs -> rs.next()
                ? rs.getString("category_label")
                : "Booking");
    }

    private ProviderLocationPhotoData providerLocationPhoto(ProviderEntityType providerEntityType, Long providerEntityId) {
        String sql = providerEntityType == ProviderEntityType.LABOUR
                ? """
                SELECT f.object_key AS photo_object_key,
                       lsa.center_latitude AS latitude,
                       lsa.center_longitude AS longitude
                FROM labour_profiles lp
                LEFT JOIN user_profiles up ON up.user_id = lp.user_id
                LEFT JOIN files f ON f.id = up.photo_file_id
                LEFT JOIN labour_service_areas lsa ON lsa.labour_id = lp.id
                WHERE lp.id = :providerEntityId
                ORDER BY lsa.id DESC
                LIMIT 1
                """
                : """
                SELECT f.object_key AS photo_object_key,
                       psa.center_latitude AS latitude,
                       psa.center_longitude AS longitude
                FROM service_providers sp
                LEFT JOIN user_profiles up ON up.user_id = sp.user_id
                LEFT JOIN files f ON f.id = up.photo_file_id
                LEFT JOIN provider_service_areas psa ON psa.provider_id = sp.id
                WHERE sp.id = :providerEntityId
                ORDER BY psa.id DESC
                LIMIT 1
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("providerEntityId", providerEntityId), rs -> {
            if (!rs.next()) {
                return null;
            }
            return new ProviderLocationPhotoData(
                    rs.getString("photo_object_key"),
                    toBigDecimal(rs.getObject("latitude")),
                    toBigDecimal(rs.getObject("longitude"))
            );
        });
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    private record ProviderLocationPhotoData(
            String photoObjectKey,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }
}
