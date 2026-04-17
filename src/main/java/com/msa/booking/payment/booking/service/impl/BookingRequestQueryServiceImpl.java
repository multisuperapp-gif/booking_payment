package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.ProviderPendingBookingRequestData;
import com.msa.booking.payment.booking.dto.UserBookingRequestStatusData;
import com.msa.booking.payment.booking.service.BookingRequestQueryService;
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

    public BookingRequestQueryServiceImpl(
            NamedParameterJdbcTemplate jdbcTemplate,
            BookingSupportRepository bookingSupportRepository,
            BookingRequestRepository bookingRequestRepository,
            BookingRequestCandidateRepository bookingRequestCandidateRepository,
            BookingRepository bookingRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookingSupportRepository = bookingSupportRepository;
        this.bookingRequestRepository = bookingRequestRepository;
        this.bookingRequestCandidateRepository = bookingRequestCandidateRepository;
        this.bookingRepository = bookingRepository;
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
                    brc.quoted_price_amount,
                    brc.distance_km,
                    br.scheduled_start_at,
                    br.expires_at
                FROM booking_request_candidates brc
                INNER JOIN booking_requests br ON br.id = brc.request_id
                INNER JOIN users u ON u.id = br.user_id
                LEFT JOIN user_profiles up ON up.user_id = u.id
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
                rs.getBigDecimal("quoted_price_amount"),
                rs.getBigDecimal("distance_km"),
                rs.getTimestamp("scheduled_start_at").toLocalDateTime(),
                rs.getTimestamp("expires_at").toLocalDateTime()
        ));
    }

    @Override
    public UserBookingRequestStatusData statusForUser(Long actingUserId, Long requestId) {
        BookingRequestEntity request = bookingRequestRepository.findById(requestId)
                .orElseThrow(() -> new BadRequestException("Booking request not found."));
        if (!actingUserId.equals(request.getUserId())) {
            throw new BadRequestException("Authenticated user cannot access this booking request.");
        }
        BookingEntity booking = bookingRepository.findByBookingRequestId(requestId).orElse(null);
        return buildStatusData(request, booking);
    }

    @Override
    public UserBookingRequestStatusData latestActiveForUser(Long actingUserId) {
        for (BookingRequestEntity request : bookingRequestRepository.findTop20ByUserIdOrderByCreatedAtDesc(actingUserId)) {
            BookingEntity booking = bookingRepository.findByBookingRequestId(request.getId()).orElse(null);
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

        Long candidateId = acceptedCandidate == null ? null : acceptedCandidate.getId();
        ProviderEntityType providerEntityType = acceptedCandidate == null ? null : acceptedCandidate.getProviderEntityType();
        Long providerEntityId = acceptedCandidate == null ? null : acceptedCandidate.getProviderEntityId();
        String providerName = null;
        String providerPhone = null;
        java.math.BigDecimal quotedPriceAmount = acceptedCandidate == null ? null : acceptedCandidate.getQuotedPriceAmount();
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
        }

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
                distanceKm,
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
}
