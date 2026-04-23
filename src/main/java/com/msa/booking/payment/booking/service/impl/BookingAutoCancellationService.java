package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.ExpireBookingRequestsResponse;
import com.msa.booking.payment.booking.service.BookingRequestService;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.domain.enums.PenaltyEntityType;
import com.msa.booking.payment.domain.enums.PenaltyType;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.domain.enums.RefundLifecycleStatus;
import com.msa.booking.payment.domain.enums.SuspensionEntityType;
import com.msa.booking.payment.domain.enums.SuspensionStatus;
import com.msa.booking.payment.modules.settlement.service.SettlementLifecycleService;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.PenaltyEntity;
import com.msa.booking.payment.persistence.entity.RefundEntity;
import com.msa.booking.payment.persistence.entity.SuspensionEntity;
import com.msa.booking.payment.persistence.entity.BookingStatusHistoryEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import com.msa.booking.payment.persistence.repository.BookingStatusHistoryRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PenaltyRepository;
import com.msa.booking.payment.persistence.repository.RefundRepository;
import com.msa.booking.payment.persistence.repository.SuspensionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class BookingAutoCancellationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingAutoCancellationService.class);
    private static final int WARNING_SCAN_LOOKBACK_MINUTES = 180;

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingSupportRepository bookingSupportRepository;
    private final BookingStatusHistoryRepository bookingStatusHistoryRepository;
    private final PenaltyRepository penaltyRepository;
    private final SuspensionRepository suspensionRepository;
    private final RefundRepository refundRepository;
    private final BookingPolicyService bookingPolicyService;
    private final BookingHistoryService bookingHistoryService;
    private final BookingRequestService bookingRequestService;
    private final NotificationService notificationService;
    private final SettlementLifecycleService settlementLifecycleService;
    private final Set<Long> warnedReachDeadlineBookingIds = ConcurrentHashMap.newKeySet();

    public BookingAutoCancellationService(
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            BookingSupportRepository bookingSupportRepository,
            BookingStatusHistoryRepository bookingStatusHistoryRepository,
            PenaltyRepository penaltyRepository,
            SuspensionRepository suspensionRepository,
            RefundRepository refundRepository,
            BookingPolicyService bookingPolicyService,
            BookingHistoryService bookingHistoryService,
            BookingRequestService bookingRequestService,
            NotificationService notificationService,
            SettlementLifecycleService settlementLifecycleService
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.bookingSupportRepository = bookingSupportRepository;
        this.bookingStatusHistoryRepository = bookingStatusHistoryRepository;
        this.penaltyRepository = penaltyRepository;
        this.suspensionRepository = suspensionRepository;
        this.refundRepository = refundRepository;
        this.bookingPolicyService = bookingPolicyService;
        this.bookingHistoryService = bookingHistoryService;
        this.bookingRequestService = bookingRequestService;
        this.notificationService = notificationService;
        this.settlementLifecycleService = settlementLifecycleService;
    }

    @Scheduled(
            initialDelayString = "${booking.auto-cancellation.initial-delay-ms:30000}",
            fixedDelayString = "${booking.auto-cancellation.scan-delay-ms:30000}"
    )
    @Transactional
    public void cancelStaleBookings() {
        int expiredRequests = expireTimedOutBookingRequests();
        int unpaid = cancelAcceptedButUnpaidBookings();
        int warnings = notifyUpcomingReachDeadlineBookings();
        int noShow = cancelProviderNoShowBookings();
        if (expiredRequests > 0 || unpaid > 0 || warnings > 0 || noShow > 0) {
            LOGGER.info(
                    "Auto-processed stale booking state. expiredRequests={}, unpaid={}, warnings={}, noShow={}",
                    expiredRequests,
                    unpaid,
                    warnings,
                    noShow
            );
        }
    }

    int expireTimedOutBookingRequests() {
        ExpireBookingRequestsResponse response = bookingRequestService.expireTimedOutRequests();
        return response == null ? 0 : response.expiredRequests();
    }

    int cancelAcceptedButUnpaidBookings() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(bookingPolicyService.acceptedPaymentTimeoutSeconds());
        List<BookingEntity> staleBookings = bookingRepository
                .findTop100ByBookingStatusAndPaymentStatusInAndCreatedAtBefore(
                        BookingLifecycleStatus.PAYMENT_PENDING,
                        List.of(PayablePaymentStatus.UNPAID, PayablePaymentStatus.PENDING),
                        cutoff
                );

        int cancelled = 0;
        for (BookingEntity booking : staleBookings) {
            try {
                cancelAcceptedButUnpaidBooking(booking);
                cancelled++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to auto-cancel unpaid booking. bookingId={}", booking.getId(), exception);
            }
        }
        return cancelled;
    }

    int cancelProviderNoShowBookings() {
        LocalDateTime now = LocalDateTime.now();
        List<BookingEntity> activePaidBookings = bookingRepository
                .findTop500ByBookingStatusAndPaymentStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                        BookingLifecycleStatus.PAYMENT_COMPLETED,
                        PayablePaymentStatus.PAID,
                        now.minusDays(2)
                );

        int cancelled = 0;
        for (BookingEntity booking : activePaidBookings) {
            LocalDateTime reachDeadline = resolveReachDeadline(booking);
            if (reachDeadline == null || now.isBefore(reachDeadline)) {
                continue;
            }
            try {
                cancelProviderNoShowBooking(booking);
                cancelled++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to auto-cancel no-show booking. bookingId={}", booking.getId(), exception);
            }
        }
        return cancelled;
    }

    int notifyUpcomingReachDeadlineBookings() {
        LocalDateTime now = LocalDateTime.now();
        List<BookingEntity> activeConfirmedBookings = bookingRepository
                .findTop500ByBookingStatusAndPaymentStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                        BookingLifecycleStatus.PAYMENT_COMPLETED,
                        PayablePaymentStatus.PAID,
                        now.minusDays(2)
                );
        Set<Long> activeIds = activeConfirmedBookings.stream()
                .map(BookingEntity::getId)
                .collect(Collectors.toSet());
        warnedReachDeadlineBookingIds.retainAll(activeIds);

        int warned = 0;
        for (BookingEntity booking : activeConfirmedBookings) {
            LocalDateTime reachByAt = resolveReachDeadline(booking);
            if (reachByAt == null) {
                continue;
            }
            LocalDateTime warningAt = reachByAt.minusMinutes(bookingPolicyService.reachWarningMinutes());
            if (now.isBefore(warningAt) || !now.isBefore(reachByAt)) {
                continue;
            }
            if (!warnedReachDeadlineBookingIds.add(booking.getId())) {
                continue;
            }
            try {
                notifyProvider(
                        booking,
                        "BOOKING_REACH_WARNING_PROVIDER",
                        "Reach in 10 minutes",
                        "Reach the customer within the next 10 minutes or this booking may be cancelled as no-show."
                );
                warned++;
            } catch (RuntimeException exception) {
                warnedReachDeadlineBookingIds.remove(booking.getId());
                LOGGER.warn("Failed to send reach warning. bookingId={}", booking.getId(), exception);
            }
        }
        return warned;
    }

    private void cancelAcceptedButUnpaidBooking(BookingEntity booking) {
        warnedReachDeadlineBookingIds.remove(booking.getId());
        String oldStatus = booking.getBookingStatus().name();
        booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
        booking.setPaymentStatus(PayablePaymentStatus.FAILED);
        bookingRepository.save(booking);
        failPendingPayment(booking);
        releaseCapacityIfNeeded(booking);
        bookingHistoryService.recordBookingStatus(
                booking,
                oldStatus,
                booking.getBookingStatus().name(),
                null,
                "Auto-cancelled because user did not complete payment after provider acceptance"
        );
        notifyUser(
                booking,
                "BOOKING_PAYMENT_TIMEOUT",
                "Booking cancelled",
                "Your booking was cancelled because payment was not completed in time."
        );
        notifyProvider(
                booking,
                "BOOKING_PAYMENT_TIMEOUT_PROVIDER",
                "Booking cancelled",
                "This booking was cancelled because the customer did not complete payment in time."
        );
    }

    private void cancelProviderNoShowBooking(BookingEntity booking) {
        warnedReachDeadlineBookingIds.remove(booking.getId());
        String oldStatus = booking.getBookingStatus().name();
        booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
        bookingRepository.save(booking);
        releaseCapacityIfNeeded(booking);
        applyFullRefundIfPaid(booking, "Provider did not reach in time");
        applyNoShowConsequences(booking);
        bookingHistoryService.recordBookingStatus(
                booking,
                oldStatus,
                booking.getBookingStatus().name(),
                booking.getUserId(),
                "Auto-cancelled because provider did not arrive within no-show window"
        );
        notifyUser(
                booking,
                "BOOKING_PROVIDER_NO_SHOW",
                "Booking cancelled",
                "Your booking was auto-cancelled because the provider did not reach in time. Refund rules were applied."
        );
        notifyProvider(
                booking,
                "BOOKING_PROVIDER_NO_SHOW_PROVIDER",
                "Booking cancelled",
                "This booking was auto-cancelled as no-show because arrival was not marked in time."
        );
    }

    private void failPendingPayment(BookingEntity booking) {
        paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING, booking.getId())
                .filter(payment -> payment.getPaymentStatus() == PaymentLifecycleStatus.INITIATED
                        || payment.getPaymentStatus() == PaymentLifecycleStatus.PENDING)
                .ifPresent(payment -> {
                    payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);
                    payment.setCompletedAt(LocalDateTime.now());
                    paymentRepository.save(payment);
                });
    }

    private void applyFullRefundIfPaid(BookingEntity booking, String reason) {
        if (booking.getPaymentStatus() != PayablePaymentStatus.PAID) {
            return;
        }
        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING, booking.getId())
                .orElse(null);
        if (payment == null) {
            return;
        }
        booking.setPaymentStatus(PayablePaymentStatus.REFUNDED);
        bookingRepository.save(booking);
        payment.setPaymentStatus(PaymentLifecycleStatus.REFUNDED);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        upsertRefund(
                payment,
                "RFN-" + payment.getPaymentCode(),
                RefundLifecycleStatus.SUCCESS,
                payment.getAmount(),
                payment.getAmount(),
                reason
        );
        settlementLifecycleService.recordSuccessfulRefund(payment, payment.getAmount());
    }

    private void upsertRefund(
            PaymentEntity payment,
            String refundCode,
            RefundLifecycleStatus refundStatus,
            BigDecimal requestedAmount,
            BigDecimal approvedAmount,
            String reason
    ) {
        Optional<RefundEntity> existingRefund = refundRepository.findTopByPaymentIdOrderByIdDesc(payment.getId());
        if (existingRefund.isPresent()) {
            RefundEntity refund = existingRefund.get();
            refund.setRefundStatus(refundStatus);
            if (refund.getRefundCode() == null || refund.getRefundCode().isBlank()) {
                refund.setRefundCode(refundCode);
            }
            if (refund.getRequestedAmount() == null) {
                refund.setRequestedAmount(requestedAmount);
            }
            refund.setApprovedAmount(approvedAmount);
            if (refund.getReason() == null || refund.getReason().isBlank()) {
                refund.setReason(reason);
            }
            if (refund.getInitiatedAt() == null) {
                refund.setInitiatedAt(LocalDateTime.now());
            }
            if (refund.getCompletedAt() == null) {
                refund.setCompletedAt(LocalDateTime.now());
            }
            refundRepository.save(refund);
            return;
        }

        RefundEntity refund = new RefundEntity();
        refund.setPaymentId(payment.getId());
        refund.setRefundCode(refundCode);
        refund.setRefundStatus(refundStatus);
        refund.setRequestedAmount(requestedAmount);
        refund.setApprovedAmount(approvedAmount);
        refund.setReason(reason);
        refund.setInitiatedAt(LocalDateTime.now());
        refund.setCompletedAt(LocalDateTime.now());
        refundRepository.save(refund);
    }

    private void applyNoShowConsequences(BookingEntity booking) {
        Long providerUserId = resolveProviderUserId(booking).orElse(null);
        if (providerUserId == null) {
            return;
        }
        PenaltyEntity penalty = new PenaltyEntity();
        penalty.setPenalizedUserId(providerUserId);
        penalty.setEntityType(booking.getProviderEntityType() == ProviderEntityType.LABOUR
                ? PenaltyEntityType.LABOUR
                : PenaltyEntityType.PROVIDER);
        penalty.setEntityId(booking.getProviderEntityId());
        penalty.setPenaltyType(PenaltyType.WARNING);
        penalty.setReason("NO_SHOW_BOOKING_" + booking.getBookingCode());
        penalty.setAmount(BigDecimal.ZERO);
        penalty.setAppliedAt(LocalDateTime.now());
        penaltyRepository.save(penalty);

        long startOfMonthCount = bookingSupportRepository.countPenaltiesSince(
                providerUserId,
                PenaltyType.WARNING,
                "NO_SHOW_",
                LocalDateTime.now().with(TemporalAdjusters.firstDayOfMonth()).withHour(0).withMinute(0).withSecond(0).withNano(0)
        );
        int threshold = booking.getProviderEntityType() == ProviderEntityType.LABOUR
                ? bookingPolicyService.labourNoShowSuspendThreshold()
                : bookingPolicyService.serviceNoShowSuspendThreshold();
        if (startOfMonthCount >= threshold
                && bookingSupportRepository.countActiveSuspensions(providerUserId, SuspensionStatus.ACTIVE, LocalDateTime.now()) == 0) {
            SuspensionEntity suspension = new SuspensionEntity();
            suspension.setUserId(providerUserId);
            suspension.setEntityType(booking.getProviderEntityType() == ProviderEntityType.LABOUR
                    ? SuspensionEntityType.LABOUR
                    : SuspensionEntityType.PROVIDER);
            suspension.setEntityId(booking.getProviderEntityId());
            suspension.setReason("Automatic suspension after repeated no-show bookings");
            suspension.setStartAt(LocalDateTime.now());
            suspension.setEndAt(LocalDateTime.now().plusDays(7));
            suspension.setStatus(SuspensionStatus.ACTIVE);
            suspensionRepository.save(suspension);
        }
    }

    private void releaseCapacityIfNeeded(BookingEntity booking) {
        if (booking.getProviderEntityType() == ProviderEntityType.SERVICE_PROVIDER) {
            bookingSupportRepository.incrementAvailableServiceMen(booking.getProviderEntityId());
        }
    }

    private LocalDateTime resolveReachDeadline(BookingEntity booking) {
        if (booking == null) {
            return null;
        }
        String categoryName = booking.getBookingType() == com.msa.booking.payment.domain.enums.BookingFlowType.LABOUR
                || booking.getBookingRequestId() == null
                ? null
                : bookingSupportRepository.findServiceCategoryNameByBookingRequestId(booking.getBookingRequestId()).orElse(null);
        BigDecimal distanceKm = booking.getBookingType() == com.msa.booking.payment.domain.enums.BookingFlowType.LABOUR
                || booking.getBookingRequestId() == null
                ? null
                : bookingSupportRepository.findAcceptedDistanceKmByBookingRequestId(
                        booking.getBookingRequestId(),
                        booking.getProviderEntityType().name(),
                        booking.getProviderEntityId()
                ).orElse(null);
        return bookingPolicyService.resolveReachDeadline(
                booking.getBookingType(),
                categoryName,
                distanceKm,
                resolvePaymentCompletedBaseTime(booking)
        );
    }

    private LocalDateTime resolvePaymentCompletedBaseTime(BookingEntity booking) {
        if (booking == null || booking.getId() == null) {
            return null;
        }
        LocalDateTime fallback = booking.getCreatedAt() != null
                ? booking.getCreatedAt()
                : booking.getScheduledStartAt();
        return bookingStatusHistoryRepository.findByBookingIdOrderByChangedAtAsc(booking.getId())
                .stream()
                .filter(entry -> BookingLifecycleStatus.PAYMENT_COMPLETED.name().equalsIgnoreCase(entry.getNewStatus()))
                .map(BookingStatusHistoryEntity::getChangedAt)
                .reduce((first, second) -> second)
                .orElse(fallback);
    }

    private void notifyUser(BookingEntity booking, String type, String title, String body) {
        notificationService.notifyUser(
                booking.getUserId(),
                type,
                title,
                body,
                Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
        );
    }

    private void notifyProvider(BookingEntity booking, String type, String title, String body) {
        resolveProviderUserId(booking).ifPresent(providerUserId -> notificationService.notifyUser(
                providerUserId,
                type,
                title,
                body,
                Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
        ));
    }

    private Optional<Long> resolveProviderUserId(BookingEntity booking) {
        return booking.getProviderEntityType() == ProviderEntityType.LABOUR
                ? bookingSupportRepository.findLabourUserId(booking.getProviderEntityId())
                : bookingSupportRepository.findServiceProviderUserId(booking.getProviderEntityId());
    }
}
