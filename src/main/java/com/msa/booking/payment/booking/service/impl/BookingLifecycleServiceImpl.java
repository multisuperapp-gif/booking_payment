package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.*;
import com.msa.booking.payment.booking.service.BookingLifecycleService;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.*;
import com.msa.booking.payment.persistence.entity.*;
import com.msa.booking.payment.persistence.repository.*;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.modules.settlement.service.SettlementLifecycleService;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.storage.BillingDocumentStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Service
public class BookingLifecycleServiceImpl implements BookingLifecycleService {
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingActionOtpRepository bookingActionOtpRepository;
    private final BookingSupportRepository bookingSupportRepository;
    private final PenaltyRepository penaltyRepository;
    private final SuspensionRepository suspensionRepository;
    private final RefundRepository refundRepository;
    private final BookingStatusHistoryRepository bookingStatusHistoryRepository;
    private final BookingPolicyService bookingPolicyService;
    private final BookingHistoryService bookingHistoryService;
    private final NotificationService notificationService;
    private final SettlementLifecycleService settlementLifecycleService;
    private final BillingDocumentStorageService billingDocumentStorageService;
    private final Random random = new Random();

    public BookingLifecycleServiceImpl(
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            BookingActionOtpRepository bookingActionOtpRepository,
            BookingSupportRepository bookingSupportRepository,
            PenaltyRepository penaltyRepository,
            SuspensionRepository suspensionRepository,
            RefundRepository refundRepository,
            BookingStatusHistoryRepository bookingStatusHistoryRepository,
            BookingPolicyService bookingPolicyService,
            BookingHistoryService bookingHistoryService,
            NotificationService notificationService,
            SettlementLifecycleService settlementLifecycleService,
            BillingDocumentStorageService billingDocumentStorageService
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.bookingActionOtpRepository = bookingActionOtpRepository;
        this.bookingSupportRepository = bookingSupportRepository;
        this.penaltyRepository = penaltyRepository;
        this.suspensionRepository = suspensionRepository;
        this.refundRepository = refundRepository;
        this.bookingStatusHistoryRepository = bookingStatusHistoryRepository;
        this.bookingPolicyService = bookingPolicyService;
        this.bookingHistoryService = bookingHistoryService;
        this.notificationService = notificationService;
        this.settlementLifecycleService = settlementLifecycleService;
        this.billingDocumentStorageService = billingDocumentStorageService;
    }

    @Override
    @Transactional(readOnly = true)
    public BookingContactPairData contacts(Long bookingId) {
        BookingEntity booking = loadBooking(bookingId);
        if (booking.getPaymentStatus() != PayablePaymentStatus.PAID) {
            throw new BadRequestException("Contacts are available only after payment is completed.");
        }

        BookingParticipantContactProjection user = bookingSupportRepository.findUserContact(booking.getUserId())
                .orElseThrow(() -> new BadRequestException("User contact not found."));
        BookingParticipantContactProjection provider = booking.getProviderEntityType() == ProviderEntityType.LABOUR
                ? bookingSupportRepository.findLabourContact(booking.getProviderEntityId())
                .orElseThrow(() -> new BadRequestException("Labour contact not found."))
                : bookingSupportRepository.findServiceProviderContact(booking.getProviderEntityId())
                .orElseThrow(() -> new BadRequestException("Service provider contact not found."));

        return new BookingContactPairData(
                booking.getBookingCode(),
                new BookingContactData(user.getUserId(), user.getFullName(), user.getPhone()),
                new BookingContactData(provider.getUserId(), provider.getFullName(), provider.getPhone())
        );
    }

    @Override
    @Transactional
    public BookingLifecycleData markArrived(Long bookingId) {
        BookingEntity booking = loadBooking(bookingId);
        if (booking.getBookingType() == BookingFlowType.SERVICE) {
            throw new BadRequestException("Arrival step is not required for service bookings.");
        }
        if (booking.getBookingStatus() != BookingLifecycleStatus.PAYMENT_COMPLETED) {
            throw new BadRequestException("Booking can be marked arrived only after payment completion.");
        }
        String oldStatus = booking.getBookingStatus().name();
        booking.setBookingStatus(BookingLifecycleStatus.ARRIVED);
        bookingRepository.save(booking);
        keepStartWorkOtpAvailableAfterArrival(booking);
        bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), resolveProviderUserId(booking), "Provider arrived");
        notificationService.notifyUser(
                booking.getUserId(),
                "BOOKING_PROVIDER_ARRIVED",
                "Provider arrived",
                "Your provider has reached the location.",
                java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
        );
        notifyProviderBookingUpdate(
                booking,
                "BOOKING_ARRIVAL_RECORDED_PROVIDER",
                "Arrival marked",
                "You marked this booking as arrived.",
                java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
        );
        return lifecycle(booking, "Provider marked arrival.");
    }

    @Override
    @Transactional
    public BookingOtpData generateOtp(GenerateBookingOtpRequest request) {
        BookingEntity booking = loadBooking(request.bookingId());
        validateOtpGeneration(booking, request.purpose());
        expireOpenOtps(booking.getId(), request.purpose());
        BookingActionOtpEntity otp = new BookingActionOtpEntity();
        otp.setBookingId(booking.getId());
        otp.setOtpPurpose(request.purpose());
        otp.setOtpCode(generateOtpCode());
        otp.setIssuedToUserId(booking.getUserId());
        otp.setOtpStatus(BookingActionOtpStatus.GENERATED);
        otp.setExpiresAt(resolveOtpExpiry(booking, request.purpose()));
        bookingActionOtpRepository.save(otp);
        if (request.purpose() == BookingOtpPurpose.MUTUAL_CANCEL) {
            notifyProviderBookingUpdate(
                    booking,
                    "BOOKING_MUTUAL_CANCEL_OTP",
                    "Mutual cancellation OTP",
                    "Share this OTP with the customer only if both sides agree to cancel. Booking charges are non-refundable after arrival.",
                    java.util.Map.of(
                            "bookingId", booking.getId(),
                            "bookingCode", booking.getBookingCode(),
                            "otpCode", otp.getOtpCode()
                    )
            );
        }
        return new BookingOtpData(booking.getId(), request.purpose(), otp.getOtpCode(), otp.getExpiresAt());
    }

    @Override
    @Transactional
    public BookingLifecycleData verifyOtpAndApply(VerifyBookingOtpRequest request) {
        BookingEntity booking = loadBooking(request.bookingId());
        BookingActionOtpEntity otp = bookingActionOtpRepository
                .findFirstByBookingIdAndOtpPurposeAndOtpCodeAndOtpStatusOrderByIdDesc(
                        request.bookingId(),
                        request.purpose(),
                        request.otpCode().trim(),
                        BookingActionOtpStatus.GENERATED
                )
                .orElseThrow(() -> new BadRequestException("Invalid booking OTP."));
        boolean ignoreExpiryForArrivedStartOtp = request.purpose() == BookingOtpPurpose.START_WORK
                && booking.getBookingType() != BookingFlowType.SERVICE
                && booking.getBookingStatus() == BookingLifecycleStatus.ARRIVED;
        if (!ignoreExpiryForArrivedStartOtp && otp.getExpiresAt().isBefore(LocalDateTime.now())) {
            otp.setOtpStatus(BookingActionOtpStatus.EXPIRED);
            bookingActionOtpRepository.save(otp);
            throw new BadRequestException("Booking OTP has expired.");
        }
        otp.setOtpStatus(BookingActionOtpStatus.USED);
        otp.setUsedAt(LocalDateTime.now());
        bookingActionOtpRepository.save(otp);

        return switch (request.purpose()) {
            case START_WORK -> {
                if (!canStartWorkWithoutArrival(booking)) {
                    throw new BadRequestException(booking.getBookingType() == BookingFlowType.SERVICE
                            ? "Start work OTP is valid after payment confirmation for service bookings."
                            : "Start work OTP is valid only after arrival.");
                }
                String oldStatus = booking.getBookingStatus().name();
                booking.setBookingStatus(BookingLifecycleStatus.IN_PROGRESS);
                bookingRepository.save(booking);
                bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), resolveProviderUserId(booking), "Work started");
                notificationService.notifyUser(
                        booking.getUserId(),
                        "BOOKING_WORK_STARTED",
                        "Work started",
                        "Your booking work has started.",
                        java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
                );
                notifyProviderBookingUpdate(
                        booking,
                        "BOOKING_WORK_STARTED_PROVIDER",
                        "Work started",
                        "You marked this booking as in progress.",
                        java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
                );
                yield lifecycle(booking, "Work started successfully.");
            }
            case COMPLETE_WORK -> {
                if (booking.getBookingStatus() != BookingLifecycleStatus.IN_PROGRESS) {
                    throw new BadRequestException("Complete work OTP is valid only after work starts.");
                }
                String oldStatus = booking.getBookingStatus().name();
                booking.setBookingStatus(BookingLifecycleStatus.COMPLETED);
                bookingRepository.save(booking);
                bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), resolveProviderUserId(booking), "Work completed");
                releaseCapacityIfNeeded(booking);
                notificationService.notifyUser(
                        booking.getUserId(),
                        "BOOKING_COMPLETED",
                        "Booking completed",
                        "Your booking has been completed successfully.",
                        java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
                );
                notifyProviderBookingUpdate(
                        booking,
                        "BOOKING_COMPLETED_PROVIDER",
                        "Booking completed",
                        "You marked this booking as completed.",
                        java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
                );
                yield lifecycle(booking, "Work completed successfully.");
            }
            case MUTUAL_CANCEL -> {
                if (booking.getBookingStatus() != BookingLifecycleStatus.ARRIVED
                        && booking.getBookingStatus() != BookingLifecycleStatus.IN_PROGRESS) {
                    throw new BadRequestException("Mutual cancellation is allowed only after arrival or work start.");
                }
                String oldStatus = booking.getBookingStatus().name();
                booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
                bookingRepository.save(booking);
                bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), booking.getUserId(), "Mutual cancellation");
                releaseCapacityIfNeeded(booking);
                applyNoRefundIfPaid(booking, "Mutual cancellation after arrival. Booking charges are non-refundable.");
                notificationService.notifyUser(
                        booking.getUserId(),
                        "BOOKING_CANCELLED",
                        "Booking cancelled",
                        "The booking was cancelled mutually. Booking charges are non-refundable after arrival.",
                        java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
                );
                notifyProviderBookingUpdate(
                        booking,
                        "BOOKING_CANCELLED_PROVIDER",
                        "Booking cancelled",
                        "This booking was cancelled mutually. Booking charges were not refunded.",
                        java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
                );
                yield lifecycle(booking, "Booking cancelled mutually.");
            }
        };
    }

    @Override
    @Transactional
    public BookingLifecycleData cancelByUser(UserCancelBookingRequest request) {
        BookingEntity booking = loadBooking(request.bookingId());
        LocalDateTime now = LocalDateTime.now();

        if (booking.getBookingStatus() == BookingLifecycleStatus.PAYMENT_COMPLETED) {
            if (!hasReachedTimelineElapsed(booking, now)) {
                throw new BadRequestException("User cancellation is allowed only after reach timeline is exceeded.");
            }
            String oldStatus = booking.getBookingStatus().name();
            booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
            bookingRepository.save(booking);
            bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), booking.getUserId(), "Cancelled after reach timeline breach");
            releaseCapacityIfNeeded(booking);
            applyFullRefundIfPaid(booking, "Provider did not reach in time");
            applyNoShowConsequences(booking);
            notificationService.notifyUser(
                    booking.getUserId(),
                    "BOOKING_CANCELLED",
                    "Booking cancelled",
                    "Your booking was cancelled because the provider did not reach in time.",
                    java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
            );
            notifyProviderBookingUpdate(
                    booking,
                    "BOOKING_CANCELLED_PROVIDER",
                    "Booking cancelled",
                    "This booking was cancelled because the reach timeline was missed.",
                    java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
            );
            return lifecycle(booking, "Booking cancelled because provider did not reach in time.");
        }

        if (booking.getBookingStatus() == BookingLifecycleStatus.IN_PROGRESS) {
            String oldStatus = booking.getBookingStatus().name();
            booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
            bookingRepository.save(booking);
            bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), booking.getUserId(), "User cancelled after work started");
            releaseCapacityIfNeeded(booking);
            applyUserCancellationPenalty(booking);
            applyProviderHalfShareNoRefund(booking);
            notificationService.notifyUser(
                    booking.getUserId(),
                    "BOOKING_CANCELLED",
                    "Booking cancelled",
                    "Your booking was cancelled after work started. Penalty rules were applied.",
                    java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
            );
            notifyProviderBookingUpdate(
                    booking,
                    "BOOKING_CANCELLED_PROVIDER",
                    "Booking cancelled",
                    "Customer cancelled this booking after work started.",
                    java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
            );
            return lifecycle(booking, "Booking cancelled by user after work start. Penalty applied.");
        }

        throw new BadRequestException("User cancellation is not allowed for the current booking state.");
    }

    @Override
    @Transactional
    public BookingReviewData submitReview(Long actingUserId, SubmitBookingReviewRequest request) {
        BookingEntity booking = loadBooking(request.bookingId());
        if (!actingUserId.equals(booking.getUserId())) {
            throw new BadRequestException("Authenticated user cannot review this booking.");
        }
        if (booking.getBookingStatus() != BookingLifecycleStatus.COMPLETED
                && booking.getBookingStatus() != BookingLifecycleStatus.CANCELLED) {
            throw new BadRequestException("Booking can be reviewed only after it is completed or cancelled.");
        }
        if (booking.getBookingStatus() == BookingLifecycleStatus.CANCELLED
                && booking.getPaymentStatus() == PayablePaymentStatus.FAILED) {
            throw new BadRequestException("Payment-failed bookings cannot be reviewed.");
        }
        if (bookingSupportRepository.countReviewsByReviewerAndBookingId(actingUserId, booking.getId()) > 0) {
            throw new BadRequestException("Review already submitted for this booking.");
        }

        String comment = request.comment() == null ? "" : request.comment().trim();
        bookingSupportRepository.insertBookingReview(
                actingUserId,
                booking.getProviderEntityType().name(),
                booking.getProviderEntityId(),
                booking.getId(),
                request.rating(),
                comment
        );
        if (booking.getProviderEntityType() == ProviderEntityType.LABOUR) {
            bookingSupportRepository.refreshLabourRating(booking.getProviderEntityId());
        } else if (booking.getProviderEntityType() == ProviderEntityType.SERVICE_PROVIDER) {
            bookingSupportRepository.refreshServiceProviderRating(booking.getProviderEntityId());
        }
        return new BookingReviewData(
                booking.getId(),
                request.rating(),
                comment,
                LocalDateTime.now()
        );
    }

    private void validateOtpGeneration(BookingEntity booking, BookingOtpPurpose purpose) {
        switch (purpose) {
            case START_WORK -> {
                if (!canStartWorkWithoutArrival(booking)) {
                    throw new BadRequestException(booking.getBookingType() == BookingFlowType.SERVICE
                            ? "Start work OTP can be generated after payment confirmation for service bookings."
                            : "Start work OTP can be generated only after arrival.");
                }
            }
            case COMPLETE_WORK -> {
                if (booking.getBookingStatus() != BookingLifecycleStatus.IN_PROGRESS) {
                    throw new BadRequestException("Complete work OTP can be generated only after work starts.");
                }
            }
            case MUTUAL_CANCEL -> {
                if (booking.getBookingStatus() != BookingLifecycleStatus.ARRIVED
                        && booking.getBookingStatus() != BookingLifecycleStatus.IN_PROGRESS) {
                    throw new BadRequestException("Mutual cancel OTP can be generated only after arrival or work start.");
                }
            }
        }
    }

    private void expireOpenOtps(Long bookingId, BookingOtpPurpose purpose) {
        List<BookingActionOtpEntity> openOtps = bookingActionOtpRepository
                .findByBookingIdAndOtpPurposeAndOtpStatus(bookingId, purpose, BookingActionOtpStatus.GENERATED);
        for (BookingActionOtpEntity otp : openOtps) {
            otp.setOtpStatus(BookingActionOtpStatus.CANCELLED);
        }
        bookingActionOtpRepository.saveAll(openOtps);
    }

    private void keepStartWorkOtpAvailableAfterArrival(BookingEntity booking) {
        if (booking.getBookingType() == BookingFlowType.SERVICE) {
            return;
        }
        List<BookingActionOtpEntity> openOtps = bookingActionOtpRepository
                .findByBookingIdAndOtpPurposeAndOtpStatus(
                        booking.getId(),
                        BookingOtpPurpose.START_WORK,
                        BookingActionOtpStatus.GENERATED
                );
        LocalDateTime extendedExpiresAt = LocalDateTime.now().plusDays(1);
        if (openOtps.isEmpty()) {
            BookingActionOtpEntity otp = new BookingActionOtpEntity();
            otp.setBookingId(booking.getId());
            otp.setOtpPurpose(BookingOtpPurpose.START_WORK);
            otp.setOtpCode(generateOtpCode());
            otp.setIssuedToUserId(booking.getUserId());
            otp.setOtpStatus(BookingActionOtpStatus.GENERATED);
            otp.setExpiresAt(extendedExpiresAt);
            bookingActionOtpRepository.save(otp);
            return;
        }
        for (BookingActionOtpEntity otp : openOtps) {
            if (otp.getExpiresAt() == null || otp.getExpiresAt().isBefore(extendedExpiresAt)) {
                otp.setExpiresAt(extendedExpiresAt);
            }
        }
        bookingActionOtpRepository.saveAll(openOtps);
    }

    private void applyNoShowConsequences(BookingEntity booking) {
        Long providerUserId = resolveProviderUserId(booking);
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

    private void applyUserCancellationPenalty(BookingEntity booking) {
        PenaltyEntity penalty = new PenaltyEntity();
        penalty.setPenalizedUserId(booking.getUserId());
        penalty.setEntityType(PenaltyEntityType.BOOKING);
        penalty.setEntityId(booking.getId());
        penalty.setPenaltyType(PenaltyType.FINE);
        penalty.setReason("USER_POST_START_CANCELLATION_" + booking.getBookingCode());
        penalty.setAmount(bookingPolicyService.postStartCancellationPenaltyAmount());
        penalty.setAppliedAt(LocalDateTime.now());
        penaltyRepository.save(penalty);
    }

    private void applyProviderHalfShareNoRefund(BookingEntity booking) {
        if (booking.getPaymentStatus() != PayablePaymentStatus.PAID) {
            return;
        }
        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING, booking.getId())
                .orElse(null);
        BigDecimal labourShare = amountOrZero(booking.getPlatformFeeAmount())
                .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
        settlementLifecycleService.recordLabourCancellationShare(
                booking,
                labourShare,
                "50% labour share from confirmed booking cancellation"
        );
        if (payment == null) {
            return;
        }
        upsertRefund(
                payment,
                "RFN-" + booking.getBookingCode(),
                RefundLifecycleStatus.REJECTED,
                payment.getAmount(),
                BigDecimal.ZERO,
                "User cancelled after work started. No refund to user; 50% of booking charge is reserved for labour."
        );
        notifyBookingRefundRejected(booking, payment);
    }

    private BigDecimal amountOrZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private void applyNoRefundIfPaid(BookingEntity booking, String reason) {
        if (booking.getPaymentStatus() != PayablePaymentStatus.PAID) {
            return;
        }
        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING, booking.getId())
                .orElse(null);
        if (payment == null) {
            return;
        }
        upsertRefund(
                payment,
                "RFN-" + booking.getBookingCode(),
                RefundLifecycleStatus.REJECTED,
                payment.getAmount(),
                BigDecimal.ZERO,
                reason
        );
        notifyBookingRefundRejected(booking, payment);
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
        RefundEntity refund = upsertRefund(
                payment,
                "RFN-" + payment.getPaymentCode(),
                RefundLifecycleStatus.SUCCESS,
                payment.getAmount(),
                payment.getAmount(),
                reason
        );
        settlementLifecycleService.recordSuccessfulRefund(payment, payment.getAmount());
        billingDocumentStorageService.storeRefundCreditNote(payment, refund, booking.getBookingCode(), "Booking refund credit note.");
        notifyBookingRefundSuccess(booking, payment);
    }

    private RefundEntity upsertRefund(
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
            return refundRepository.save(refund);
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
        return refundRepository.save(refund);
    }

    private boolean hasReachedTimelineElapsed(BookingEntity booking, LocalDateTime now) {
        LocalDateTime reachDeadline = resolveReachDeadline(booking);
        return reachDeadline != null && !now.isBefore(reachDeadline);
    }

    private boolean canStartWorkWithoutArrival(BookingEntity booking) {
        if (booking == null) {
            return false;
        }
        BookingLifecycleStatus status = booking.getBookingStatus();
        if (booking.getBookingType() == BookingFlowType.SERVICE) {
            return status == BookingLifecycleStatus.PAYMENT_COMPLETED
                    || status == BookingLifecycleStatus.ARRIVED;
        }
        return status == BookingLifecycleStatus.ARRIVED;
    }

    private LocalDateTime resolvePaymentCompletedBaseTime(BookingEntity booking) {
        if (booking == null || booking.getId() == null) {
            return null;
        }
        LocalDateTime fallback = booking.getCreatedAt() != null
                ? booking.getCreatedAt()
                : booking.getScheduledStartAt();
        return bookingStatusHistoryRepository
                .findByBookingIdOrderByChangedAtAsc(booking.getId())
                .stream()
                .filter(entry -> BookingLifecycleStatus.PAYMENT_COMPLETED.name().equalsIgnoreCase(entry.getNewStatus()))
                .map(BookingStatusHistoryEntity::getChangedAt)
                .reduce((first, second) -> second)
                .orElse(fallback);
    }

    private LocalDateTime resolveReachDeadline(BookingEntity booking) {
        if (booking == null) {
            return null;
        }
        String categoryName = resolveServiceCategoryName(booking);
        BigDecimal distanceKm = resolveAcceptedDistanceKm(booking);
        return bookingPolicyService.resolveReachDeadline(
                booking.getBookingType(),
                categoryName,
                distanceKm,
                resolvePaymentCompletedBaseTime(booking)
        );
    }

    private LocalDateTime resolveOtpExpiry(BookingEntity booking, BookingOtpPurpose purpose) {
        if (purpose != BookingOtpPurpose.START_WORK) {
            return LocalDateTime.now().plusMinutes(10);
        }
        if (booking == null) {
            return LocalDateTime.now().plusMinutes(10);
        }
        if (booking.getBookingType() == BookingFlowType.SERVICE) {
            LocalDateTime baseTime = resolvePaymentCompletedBaseTime(booking);
            LocalDateTime expiresAt = bookingPolicyService.resolveServiceStartWorkOtpExpiry(
                    resolveServiceCategoryName(booking),
                    resolveAcceptedDistanceKm(booking),
                    baseTime
            );
            return expiresAt == null ? LocalDateTime.now().plusMinutes(bookingPolicyService.serviceDefaultReachTimelineMinutes()) : expiresAt;
        }
        if (booking.getBookingStatus() == BookingLifecycleStatus.ARRIVED) {
            return LocalDateTime.now().plusDays(1);
        }
        LocalDateTime fallbackBase = booking.getScheduledStartAt() != null ? booking.getScheduledStartAt() : LocalDateTime.now();
        return fallbackBase.plusMinutes(bookingPolicyService.noShowAutoCancelMinutes());
    }

    private String resolveServiceCategoryName(BookingEntity booking) {
        if (booking == null || booking.getBookingType() == BookingFlowType.LABOUR || booking.getBookingRequestId() == null) {
            return null;
        }
        return bookingSupportRepository.findServiceCategoryNameByBookingRequestId(booking.getBookingRequestId()).orElse(null);
    }

    private BigDecimal resolveAcceptedDistanceKm(BookingEntity booking) {
        if (booking == null || booking.getBookingType() == BookingFlowType.LABOUR || booking.getBookingRequestId() == null) {
            return null;
        }
        return bookingSupportRepository.findAcceptedDistanceKmByBookingRequestId(
                booking.getBookingRequestId(),
                booking.getProviderEntityType().name(),
                booking.getProviderEntityId()
        ).orElse(null);
    }

    private void releaseCapacityIfNeeded(BookingEntity booking) {
        if (booking.getProviderEntityType() == ProviderEntityType.SERVICE_PROVIDER) {
            bookingSupportRepository.incrementAvailableServiceMen(booking.getProviderEntityId());
        }
    }

    private Long resolveProviderUserId(BookingEntity booking) {
        return booking.getProviderEntityType() == ProviderEntityType.LABOUR
                ? bookingSupportRepository.findLabourUserId(booking.getProviderEntityId())
                .orElseThrow(() -> new BadRequestException("Labour user not found."))
                : bookingSupportRepository.findServiceProviderUserId(booking.getProviderEntityId())
                .orElseThrow(() -> new BadRequestException("Service provider user not found."));
    }

    private void notifyProviderBookingUpdate(BookingEntity booking, String type, String title, String body, java.util.Map<String, Object> payload) {
        java.util.Map<String, Object> providerPayload = new java.util.LinkedHashMap<>(payload);
        providerPayload.put("appContext", "PROVIDER_APP");
        notificationService.notifyUser(
                resolveProviderUserId(booking),
                type,
                title,
                body,
                providerPayload
        );
    }

    private BookingEntity loadBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BadRequestException("Booking not found."));
    }

    private void notifyBookingRefundSuccess(BookingEntity booking, PaymentEntity payment) {
        java.util.Map<String, Object> payload = java.util.Map.of(
                "bookingId", booking.getId(),
                "bookingCode", booking.getBookingCode(),
                "paymentCode", payment.getPaymentCode()
        );
        notificationService.notifyUser(
                booking.getUserId(),
                "BOOKING_REFUND_SUCCESS",
                "Refund completed",
                "Your refund has been completed for the cancelled booking.",
                payload
        );
        notifyProviderBookingUpdate(
                booking,
                "BOOKING_REFUND_SUCCESS_PROVIDER",
                "Refund completed",
                "The customer refund for this booking has been completed.",
                payload
        );
    }

    private void notifyBookingRefundRejected(BookingEntity booking, PaymentEntity payment) {
        java.util.Map<String, Object> payload = java.util.Map.of(
                "bookingId", booking.getId(),
                "bookingCode", booking.getBookingCode(),
                "paymentCode", payment.getPaymentCode()
        );
        notificationService.notifyUser(
                booking.getUserId(),
                "BOOKING_REFUND_REJECTED",
                "No refund applicable",
                "This booking cancellation does not qualify for a refund under the current policy.",
                payload
        );
        notifyProviderBookingUpdate(
                booking,
                "BOOKING_REFUND_REJECTED_PROVIDER",
                "Refund not applicable",
                "No refund was applied for this booking under the current policy.",
                payload
        );
    }

    private String generateOtpCode() {
        return String.valueOf(100000 + random.nextInt(900000));
    }

    private BookingLifecycleData lifecycle(BookingEntity booking, String note) {
        return new BookingLifecycleData(
                booking.getId(),
                booking.getBookingCode(),
                booking.getBookingStatus(),
                booking.getPaymentStatus(),
                note
        );
    }
}
