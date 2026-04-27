package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.ProviderActiveBookingData;
import com.msa.booking.payment.booking.dto.ProviderBookingHistoryData;
import com.msa.booking.payment.booking.dto.ProviderPendingBookingRequestData;
import com.msa.booking.payment.booking.dto.UserBookingRequestStatusData;
import com.msa.booking.payment.booking.service.BookingRequestQueryService;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingActionOtpStatus;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.BookingRequestCandidateStatus;
import com.msa.booking.payment.domain.enums.BookingRequestStatus;
import com.msa.booking.payment.domain.enums.BookingOtpPurpose;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.persistence.entity.BookingActionOtpEntity;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestCandidateEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import com.msa.booking.payment.persistence.entity.BookingStatusHistoryEntity;
import com.msa.booking.payment.persistence.repository.BookingActionOtpRepository;
import com.msa.booking.payment.persistence.repository.BookingParticipantContactProjection;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestCandidateRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestRepository;
import com.msa.booking.payment.persistence.repository.BookingStatusHistoryRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

@Service
public class BookingRequestQueryServiceImpl implements BookingRequestQueryService {
    private final BookingActionOtpRepository bookingActionOtpRepository;
    private final BookingSupportRepository bookingSupportRepository;
    private final BookingRequestRepository bookingRequestRepository;
    private final BookingRequestCandidateRepository bookingRequestCandidateRepository;
    private final BookingRepository bookingRepository;
    private final BookingStatusHistoryRepository bookingStatusHistoryRepository;
    private final BookingPolicyService bookingPolicyService;

    public BookingRequestQueryServiceImpl(
            BookingActionOtpRepository bookingActionOtpRepository,
            BookingSupportRepository bookingSupportRepository,
            BookingRequestRepository bookingRequestRepository,
            BookingRequestCandidateRepository bookingRequestCandidateRepository,
            BookingRepository bookingRepository,
            BookingStatusHistoryRepository bookingStatusHistoryRepository,
            BookingPolicyService bookingPolicyService
    ) {
        this.bookingActionOtpRepository = bookingActionOtpRepository;
        this.bookingSupportRepository = bookingSupportRepository;
        this.bookingRequestRepository = bookingRequestRepository;
        this.bookingRequestCandidateRepository = bookingRequestCandidateRepository;
        this.bookingRepository = bookingRepository;
        this.bookingStatusHistoryRepository = bookingStatusHistoryRepository;
        this.bookingPolicyService = bookingPolicyService;
    }

    @Override
    public List<ProviderPendingBookingRequestData> pendingForProvider(
            Long actingUserId,
            ProviderEntityType providerEntityType
    ) {
        return bookingSupportRepository.findPendingBookingRequestsForProvider(actingUserId, providerEntityType.name()).stream()
                .map(row -> new ProviderPendingBookingRequestData(
                        row.getRequestId(),
                        row.getRequestCode(),
                        BookingFlowType.valueOf(row.getBookingType()),
                        ProviderEntityType.valueOf(row.getProviderEntityType()),
                        row.getProviderEntityId(),
                        row.getCandidateId(),
                        row.getCustomerName(),
                        row.getCategoryLabel(),
                        row.getLabourPricingModel(),
                        row.getQuotedPriceAmount(),
                        row.getDistanceKm(),
                        toLocalDateTime(row.getScheduledStartAt()),
                        toLocalDateTime(row.getExpiresAt())
                ))
                .toList();
    }

    @Override
    public ProviderActiveBookingData latestActiveForProvider(Long actingUserId, ProviderEntityType providerEntityType) {
        ProviderActiveBookingData raw = bookingSupportRepository.findLatestActiveBookingForProvider(
                        actingUserId,
                        providerEntityType.name()
                )
                .map(this::toProviderActiveBookingData)
                .orElse(null);
        if (raw == null) {
            return null;
        }

        String startOtpCode = raw.startOtpCode();
        LocalDateTime startOtpExpiresAt = raw.startOtpExpiresAt();
        if ((raw.bookingStatus() == BookingLifecycleStatus.PAYMENT_COMPLETED
                || raw.bookingStatus() == BookingLifecycleStatus.ARRIVED)
                && (startOtpCode == null || startOtpCode.isBlank())) {
            StartOtpData generatedStartOtp = prepareMissingStartOtp(
                    raw.bookingId(),
                    raw.bookingType(),
                    raw.categoryLabel(),
                    raw.distanceKm(),
                    resolvePaymentCompletedBaseTime(
                            raw.bookingId(),
                            raw.scheduledStartAt()
                    ),
                    raw.reachByAt()
            );
            startOtpCode = generatedStartOtp.otpCode();
            startOtpExpiresAt = generatedStartOtp.expiresAt();
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
                raw.providerLatitude(),
                raw.providerLongitude(),
                raw.destinationLatitude(),
                raw.destinationLongitude(),
                startOtpCode,
                startOtpExpiresAt,
                raw.completeOtpCode(),
                raw.completeOtpExpiresAt(),
                raw.mutualCancelOtpCode(),
                raw.mutualCancelOtpExpiresAt()
        );
    }

    @Override
    public List<ProviderBookingHistoryData> historyForProvider(Long actingUserId, ProviderEntityType providerEntityType) {
        return bookingSupportRepository.findProviderBookingHistory(actingUserId, providerEntityType.name()).stream()
                .map(row -> new ProviderBookingHistoryData(
                        row.getBookingId(),
                        row.getBookingCode(),
                        BookingFlowType.valueOf(row.getBookingType()),
                        ProviderEntityType.valueOf(row.getProviderEntityType()),
                        row.getProviderEntityId(),
                        BookingLifecycleStatus.valueOf(row.getBookingStatus()),
                        PayablePaymentStatus.valueOf(row.getPaymentStatus()),
                        row.getCustomerName(),
                        maskPhone(row.getCustomerPhone()),
                        row.getQuotedPriceAmount(),
                        row.getPlatformFeeAmount(),
                        row.getDistanceKm(),
                        toLocalDateTime(row.getScheduledStartAt()),
                        toLocalDateTime(row.getCreatedAt()),
                        row.getCategoryLabel(),
                        row.getLabourPricingModel()
                ))
                .toList();
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
    public List<UserBookingRequestStatusData> activeForUser(Long actingUserId) {
        java.util.List<UserBookingRequestStatusData> activeStatuses = new java.util.ArrayList<>();
        for (BookingRequestEntity request : bookingRequestRepository.findTop20ByUserIdOrderByCreatedAtDesc(actingUserId)) {
            BookingEntity booking = bookingRepository.findByBookingRequestIdOrderByIdAsc(request.getId())
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (isActiveForUser(request, booking)) {
                activeStatuses.add(buildStatusData(request, booking));
            }
        }
        return activeStatuses;
    }

    @Override
    public UserBookingRequestStatusData latestActiveForUser(Long actingUserId) {
        return activeForUser(actingUserId).stream().findFirst().orElse(null);
    }

    @Override
    public List<UserBookingRequestStatusData> historyForUser(Long actingUserId) {
        java.util.List<UserBookingRequestStatusData> history = new java.util.ArrayList<>();
        for (BookingRequestEntity request : bookingRequestRepository.findTop50ByUserIdOrderByCreatedAtDesc(actingUserId)) {
            BookingEntity booking = bookingRepository.findByBookingRequestIdOrderByIdAsc(request.getId())
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (booking == null || booking.getBookingStatus() == null) {
                continue;
            }
            if (booking.getBookingStatus() != BookingLifecycleStatus.COMPLETED
                    && booking.getBookingStatus() != BookingLifecycleStatus.CANCELLED) {
                continue;
            }
            history.add(buildStatusData(request, booking));
        }
        return history;
    }

    private boolean isActiveForUser(BookingRequestEntity request, BookingEntity booking) {
        if (request.getRequestStatus() == BookingRequestStatus.ACCEPTED) {
            return true;
        }
        if (request.getRequestStatus() == BookingRequestStatus.OPEN) {
            return booking != null;
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
        ProviderEntityType providerEntityType = booking != null && booking.getProviderEntityType() != null
                ? booking.getProviderEntityType()
                : (acceptedCandidate == null ? null : acceptedCandidate.getProviderEntityType());
        Long providerEntityId = booking != null && booking.getProviderEntityId() != null
                ? booking.getProviderEntityId()
                : (acceptedCandidate == null ? null : acceptedCandidate.getProviderEntityId());
        String providerName = null;
        String providerPhone = null;
        ProviderLocationPhotoData providerLocationPhotoData = null;
        java.math.BigDecimal quotedPriceAmount = acceptedCandidate != null
                ? acceptedCandidate.getQuotedPriceAmount()
                : (booking == null ? null : booking.getSubtotalAmount());
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

        if (providerEntityType != null && providerEntityId != null) {
            BookingParticipantContactProjection contact = providerEntityType == ProviderEntityType.LABOUR
                    ? bookingSupportRepository.findLabourContact(providerEntityId).orElse(null)
                    : bookingSupportRepository.findServiceProviderContact(providerEntityId).orElse(null);
            if (contact != null) {
                providerName = contact.getFullName();
                boolean revealPhone = booking != null && booking.getPaymentStatus() == PayablePaymentStatus.PAID;
                providerPhone = revealPhone ? contact.getPhone() : maskPhone(contact.getPhone());
            }
            providerLocationPhotoData = providerLocationPhoto(
                    providerEntityType,
                    providerEntityId
            );
        }

        LocalDateTime paymentDueAt = booking == null || booking.getCreatedAt() == null
                ? null
                : booking.getCreatedAt().plusSeconds(bookingPolicyService.acceptedPaymentTimeoutSeconds());
        String categoryLabel = resolveCategoryLabel(request);
        LocalDateTime reachByAt = resolveReachByAt(booking, request.getBookingType(), categoryLabel, distanceKm);
        boolean revealProviderLiveLocation = booking != null && booking.getPaymentStatus() == PayablePaymentStatus.PAID;
        String historyStatus = resolveHistoryStatus(booking);
        boolean reviewSubmitted = booking != null
                && bookingSupportRepository.countReviewsByReviewerAndBookingId(request.getUserId(), booking.getId()) > 0;

        return new UserBookingRequestStatusData(
                request.getId(),
                request.getRequestCode(),
                request.getBookingType(),
                request.getRequestStatus(),
                historyStatus,
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
                request.getSearchLatitude(),
                request.getSearchLongitude(),
                paymentDueAt,
                reachByAt,
                request.getLabourPricingModel(),
                request.getRequestedProviderCount() == null ? 1 : request.getRequestedProviderCount(),
                acceptedProviderCount,
                pendingProviderCount,
                booking == null ? null : booking.getId(),
                booking == null ? null : booking.getBookingCode(),
                booking == null ? null : booking.getBookingStatus(),
                booking == null ? null : booking.getPaymentStatus(),
                request.getCreatedAt(),
                reviewSubmitted
        );
    }

    private String resolveHistoryStatus(BookingEntity booking) {
        if (booking == null || booking.getBookingStatus() == null) {
            return "";
        }
        if (booking.getBookingStatus() == BookingLifecycleStatus.COMPLETED) {
            return "COMPLETED";
        }
        if (booking.getBookingStatus() == BookingLifecycleStatus.CANCELLED) {
            if (booking.getPaymentStatus() == PayablePaymentStatus.FAILED) {
                return "PAYMENT_FAILED";
            }
            BookingStatusHistoryEntity latestHistory = bookingStatusHistoryRepository
                    .findByBookingIdOrderByChangedAtAsc(booking.getId())
                    .stream()
                    .reduce((first, second) -> second)
                    .orElse(null);
            String reason = latestHistory == null || latestHistory.getReason() == null
                    ? ""
                    : latestHistory.getReason().trim().toUpperCase();
            if (reason.contains("NO_SHOW") || reason.contains("NO SHOW")) {
                return "NO_SHOW";
            }
            return "CANCELLED";
        }
        return booking.getBookingStatus().name();
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

    private LocalDateTime resolvePaymentCompletedBaseTime(Long bookingId, LocalDateTime fallback) {
        if (bookingId == null) {
            return fallback;
        }
        return bookingStatusHistoryRepository
                .findByBookingIdOrderByChangedAtAsc(bookingId)
                .stream()
                .filter(entry -> BookingLifecycleStatus.PAYMENT_COMPLETED.name().equalsIgnoreCase(entry.getNewStatus()))
                .map(BookingStatusHistoryEntity::getChangedAt)
                .reduce((first, second) -> second)
                .orElse(fallback);
    }

    private LocalDateTime resolveReachByAt(BookingEntity booking, BookingFlowType bookingType, String categoryLabel, BigDecimal distanceKm) {
        if (booking == null) {
            return null;
        }
        LocalDateTime fallbackBaseTime = booking.getCreatedAt() != null ? booking.getCreatedAt() : booking.getScheduledStartAt();
        LocalDateTime baseTime = resolvePaymentCompletedBaseTime(booking.getId(), fallbackBaseTime);
        return bookingPolicyService.resolveReachDeadline(bookingType, categoryLabel, distanceKm, baseTime);
    }

    private StartOtpData prepareMissingStartOtp(
            Long bookingId,
            BookingFlowType bookingType,
            String categoryLabel,
            BigDecimal distanceKm,
            LocalDateTime paymentCompletedAt,
            LocalDateTime reachByAt
    ) {
        bookingActionOtpRepository.updateStatusByBookingIdAndPurpose(
                bookingId,
                BookingOtpPurpose.START_WORK,
                BookingActionOtpStatus.GENERATED,
                BookingActionOtpStatus.CANCELLED
        );

        String otpCode = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1_000_000));
        LocalDateTime expiresAt;
        if (bookingType == BookingFlowType.SERVICE) {
            expiresAt = bookingPolicyService.resolveServiceStartWorkOtpExpiry(categoryLabel, distanceKm, paymentCompletedAt);
            if (expiresAt == null && reachByAt != null && "automobile".equalsIgnoreCase(categoryLabel == null ? "" : categoryLabel.trim())) {
                expiresAt = reachByAt.plusHours(1);
            }
            if (expiresAt == null) {
                expiresAt = LocalDateTime.now().plusMinutes(bookingPolicyService.serviceDefaultReachTimelineMinutes());
            }
        } else {
            expiresAt = reachByAt == null
                    ? LocalDateTime.now().plusMinutes(bookingPolicyService.noShowAutoCancelMinutes())
                    : reachByAt.plusMinutes(bookingPolicyService.noShowAutoCancelMinutes());
        }
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BadRequestException("Booking not found."));
        BookingActionOtpEntity otp = new BookingActionOtpEntity();
        otp.setBookingId(bookingId);
        otp.setOtpPurpose(BookingOtpPurpose.START_WORK);
        otp.setOtpCode(otpCode);
        otp.setIssuedToUserId(booking.getUserId());
        otp.setOtpStatus(BookingActionOtpStatus.GENERATED);
        otp.setExpiresAt(expiresAt);
        bookingActionOtpRepository.save(otp);
        return new StartOtpData(otpCode, expiresAt);
    }

    private String resolveCategoryLabel(BookingRequestEntity request) {
        return bookingSupportRepository.findBookingCategoryLabelByRequestId(request.getId())
                .orElse("Booking");
    }

    private ProviderLocationPhotoData providerLocationPhoto(ProviderEntityType providerEntityType, Long providerEntityId) {
        return (providerEntityType == ProviderEntityType.LABOUR
                ? bookingSupportRepository.findLabourLocationPhoto(providerEntityId)
                : bookingSupportRepository.findServiceProviderLocationPhoto(providerEntityId))
                .map(row -> new ProviderLocationPhotoData(
                        row.getPhotoObjectKey(),
                        row.getLatitude(),
                        row.getLongitude()
                ))
                .orElse(null);
    }

    private ProviderActiveBookingData toProviderActiveBookingData(BookingSupportRepository.ProviderActiveBookingView row) {
        LocalDateTime scheduledStartAt = toLocalDateTime(row.getScheduledStartAt());
        LocalDateTime createdAt = toLocalDateTime(row.getCreatedAt());
        BookingFlowType bookingType = BookingFlowType.valueOf(row.getBookingType());
        String categoryLabel = row.getCategoryLabel();
        BigDecimal distanceKm = row.getDistanceKm();
        LocalDateTime paymentBaseTime = resolvePaymentCompletedBaseTime(
                row.getBookingId(),
                createdAt != null ? createdAt : scheduledStartAt
        );
        LocalDateTime reachByAt = bookingPolicyService.resolveReachDeadline(
                bookingType,
                categoryLabel,
                distanceKm,
                paymentBaseTime
        );
        return new ProviderActiveBookingData(
                row.getBookingId(),
                row.getBookingCode(),
                bookingType,
                ProviderEntityType.valueOf(row.getProviderEntityType()),
                row.getProviderEntityId(),
                BookingLifecycleStatus.valueOf(row.getBookingStatus()),
                PayablePaymentStatus.valueOf(row.getPaymentStatus()),
                row.getCustomerName(),
                row.getCustomerPhone(),
                row.getQuotedPriceAmount(),
                distanceKm,
                scheduledStartAt,
                createdAt == null ? null : createdAt.plusSeconds(bookingPolicyService.acceptedPaymentTimeoutSeconds()),
                reachByAt,
                categoryLabel,
                row.getLabourPricingModel(),
                row.getAddressLabel(),
                row.getAddressLine1(),
                row.getAddressLine2(),
                row.getLandmark(),
                row.getCity(),
                row.getState(),
                row.getPostalCode(),
                row.getProviderLatitude(),
                row.getProviderLongitude(),
                row.getLatitude(),
                row.getLongitude(),
                row.getStartOtpCode(),
                toLocalDateTime(row.getStartOtpExpiresAt()),
                row.getCompleteOtpCode(),
                toLocalDateTime(row.getCompleteOtpExpiresAt()),
                row.getMutualCancelOtpCode(),
                toLocalDateTime(row.getMutualCancelOtpExpiresAt())
        );
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record ProviderLocationPhotoData(
            String photoObjectKey,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }

    private record StartOtpData(
            String otpCode,
            LocalDateTime expiresAt
    ) {
    }
}
