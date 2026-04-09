package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.*;
import com.msa.booking.payment.booking.service.BookingLifecycleService;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.*;
import com.msa.booking.payment.persistence.entity.*;
import com.msa.booking.payment.persistence.repository.*;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.notification.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Random;

@Service
public class BookingLifecycleServiceImpl implements BookingLifecycleService {
    private static final int OTP_EXPIRY_MINUTES = 10;

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingActionOtpRepository bookingActionOtpRepository;
    private final BookingSupportRepository bookingSupportRepository;
    private final PenaltyRepository penaltyRepository;
    private final SuspensionRepository suspensionRepository;
    private final RefundRepository refundRepository;
    private final BookingPolicyService bookingPolicyService;
    private final BookingHistoryService bookingHistoryService;
    private final NotificationService notificationService;
    private final Random random = new Random();

    public BookingLifecycleServiceImpl(
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            BookingActionOtpRepository bookingActionOtpRepository,
            BookingSupportRepository bookingSupportRepository,
            PenaltyRepository penaltyRepository,
            SuspensionRepository suspensionRepository,
            RefundRepository refundRepository,
            BookingPolicyService bookingPolicyService,
            BookingHistoryService bookingHistoryService,
            NotificationService notificationService
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.bookingActionOtpRepository = bookingActionOtpRepository;
        this.bookingSupportRepository = bookingSupportRepository;
        this.penaltyRepository = penaltyRepository;
        this.suspensionRepository = suspensionRepository;
        this.refundRepository = refundRepository;
        this.bookingPolicyService = bookingPolicyService;
        this.bookingHistoryService = bookingHistoryService;
        this.notificationService = notificationService;
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
        if (booking.getBookingStatus() != BookingLifecycleStatus.PAYMENT_COMPLETED) {
            throw new BadRequestException("Booking can be marked arrived only after payment completion.");
        }
        String oldStatus = booking.getBookingStatus().name();
        booking.setBookingStatus(BookingLifecycleStatus.ARRIVED);
        bookingRepository.save(booking);
        bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), resolveProviderUserId(booking), "Provider arrived");
        notificationService.notifyUser(
                booking.getUserId(),
                "BOOKING_PROVIDER_ARRIVED",
                "Provider arrived",
                "Your provider has reached the location.",
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
        otp.setExpiresAt(LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES));
        bookingActionOtpRepository.save(otp);
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
        if (otp.getExpiresAt().isBefore(LocalDateTime.now())) {
            otp.setOtpStatus(BookingActionOtpStatus.EXPIRED);
            bookingActionOtpRepository.save(otp);
            throw new BadRequestException("Booking OTP has expired.");
        }
        otp.setOtpStatus(BookingActionOtpStatus.USED);
        otp.setUsedAt(LocalDateTime.now());
        bookingActionOtpRepository.save(otp);

        return switch (request.purpose()) {
            case START_WORK -> {
                if (booking.getBookingStatus() != BookingLifecycleStatus.ARRIVED) {
                    throw new BadRequestException("Start work OTP is valid only after arrival.");
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
                yield lifecycle(booking, "Work completed successfully.");
            }
            case MUTUAL_CANCEL -> {
                if (booking.getBookingStatus() == BookingLifecycleStatus.COMPLETED
                        || booking.getBookingStatus() == BookingLifecycleStatus.CANCELLED) {
                    throw new BadRequestException("Mutual cancellation is not allowed for this booking.");
                }
                String oldStatus = booking.getBookingStatus().name();
                booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
                bookingRepository.save(booking);
                bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), booking.getUserId(), "Mutual cancellation");
                releaseCapacityIfNeeded(booking);
                applyFullRefundIfPaid(booking, "Mutual cancellation");
                notificationService.notifyUser(
                        booking.getUserId(),
                        "BOOKING_CANCELLED",
                        "Booking cancelled",
                        "The booking was cancelled mutually.",
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
            return lifecycle(booking, "Booking cancelled by user after work start. Penalty applied.");
        }

        throw new BadRequestException("User cancellation is not allowed for the current booking state.");
    }

    private void validateOtpGeneration(BookingEntity booking, BookingOtpPurpose purpose) {
        switch (purpose) {
            case START_WORK -> {
                if (booking.getBookingStatus() != BookingLifecycleStatus.ARRIVED) {
                    throw new BadRequestException("Start work OTP can be generated only after arrival.");
                }
            }
            case COMPLETE_WORK -> {
                if (booking.getBookingStatus() != BookingLifecycleStatus.IN_PROGRESS) {
                    throw new BadRequestException("Complete work OTP can be generated only after work starts.");
                }
            }
            case MUTUAL_CANCEL -> {
                if (booking.getBookingStatus() == BookingLifecycleStatus.COMPLETED
                        || booking.getBookingStatus() == BookingLifecycleStatus.CANCELLED) {
                    throw new BadRequestException("Mutual cancel OTP is not allowed for this booking.");
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
        if (payment == null) {
            return;
        }
        RefundEntity refund = new RefundEntity();
        refund.setPaymentId(payment.getId());
        refund.setRefundCode("RFN-" + booking.getBookingCode());
        refund.setRefundStatus(RefundLifecycleStatus.REJECTED);
        refund.setRequestedAmount(payment.getAmount());
        refund.setApprovedAmount(BigDecimal.ZERO);
        refund.setReason("User cancelled after work started. No refund to user; provider half-share applies offline.");
        refund.setInitiatedAt(LocalDateTime.now());
        refund.setCompletedAt(LocalDateTime.now());
        refundRepository.save(refund);
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

        RefundEntity refund = new RefundEntity();
        refund.setPaymentId(payment.getId());
        refund.setRefundCode("RFN-" + payment.getPaymentCode());
        refund.setRefundStatus(RefundLifecycleStatus.SUCCESS);
        refund.setRequestedAmount(payment.getAmount());
        refund.setApprovedAmount(payment.getAmount());
        refund.setReason(reason);
        refund.setInitiatedAt(LocalDateTime.now());
        refund.setCompletedAt(LocalDateTime.now());
        refundRepository.save(refund);
    }

    private boolean hasReachedTimelineElapsed(BookingEntity booking, LocalDateTime now) {
        int minutes;
        if (booking.getBookingType() == BookingFlowType.LABOUR) {
            minutes = bookingPolicyService.labourReachTimelineMinutes();
        } else {
            String categoryName = booking.getBookingRequestId() == null
                    ? null
                    : bookingSupportRepository.findServiceCategoryNameByBookingRequestId(booking.getBookingRequestId()).orElse(null);
            minutes = "automobile".equalsIgnoreCase(categoryName)
                    ? bookingPolicyService.serviceAutomobileReachTimelineMinutes()
                    : bookingPolicyService.serviceDefaultReachTimelineMinutes();
        }
        return now.isAfter(booking.getScheduledStartAt().plusMinutes(minutes));
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

    private BookingEntity loadBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BadRequestException("Booking not found."));
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
