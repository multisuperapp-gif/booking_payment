package com.msa.booking.payment.payment.service.impl;

import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingActionOtpStatus;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.BookingOtpPurpose;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.persistence.entity.BookingActionOtpEntity;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentTransactionEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import com.msa.booking.payment.persistence.repository.BookingActionOtpRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestRepository;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.payment.dto.BookingPaymentData;
import com.msa.booking.payment.payment.dto.CompleteBookingPaymentRequest;
import com.msa.booking.payment.payment.dto.InitiateBookingPaymentRequest;
import com.msa.booking.payment.payment.service.BookingPaymentService;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentTransactionStatus;
import com.msa.booking.payment.domain.enums.PaymentTransactionType;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class BookingPaymentServiceImpl implements BookingPaymentService {
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final BookingSupportRepository bookingSupportRepository;
    private final BookingActionOtpRepository bookingActionOtpRepository;
    private final BookingRequestRepository bookingRequestRepository;
    private final BookingPolicyService bookingPolicyService;
    private final BookingHistoryService bookingHistoryService;
    private final NotificationService notificationService;
    private final RazorpayGatewayService razorpayGatewayService;

    public BookingPaymentServiceImpl(
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            BookingSupportRepository bookingSupportRepository,
            BookingActionOtpRepository bookingActionOtpRepository,
            BookingRequestRepository bookingRequestRepository,
            BookingPolicyService bookingPolicyService,
            BookingHistoryService bookingHistoryService,
            NotificationService notificationService,
            RazorpayGatewayService razorpayGatewayService
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.bookingSupportRepository = bookingSupportRepository;
        this.bookingActionOtpRepository = bookingActionOtpRepository;
        this.bookingRequestRepository = bookingRequestRepository;
        this.bookingPolicyService = bookingPolicyService;
        this.bookingHistoryService = bookingHistoryService;
        this.notificationService = notificationService;
        this.razorpayGatewayService = razorpayGatewayService;
    }

    @Override
    @Transactional
    public BookingPaymentData initiate(InitiateBookingPaymentRequest request) {
        if (request.bookingRequestId() != null) {
            return initiateForBookingRequest(request.bookingRequestId());
        }
        if (request.bookingId() == null) {
            throw new BadRequestException("Booking id is required.");
        }
        BookingEntity booking = loadBooking(request.bookingId());
        if (booking.getBookingStatus() != BookingLifecycleStatus.PAYMENT_PENDING) {
            throw new BadRequestException("Payment can be initiated only when booking is waiting for payment.");
        }

        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING, booking.getId())
                .orElseGet(() -> createPayment(booking));
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS) {
            throw new BadRequestException("Payment is already completed for this booking.");
        }
        PaymentAttemptEntity latestAttempt = paymentAttemptRepository
                .findTopByPaymentIdAndGatewayNameOrderByIdDesc(payment.getId(), "RAZORPAY")
                .orElse(null);
        if (isReusablePendingAttempt(latestAttempt)) {
            payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
            paymentRepository.save(payment);
            booking.setPaymentStatus(PayablePaymentStatus.PENDING);
            bookingRepository.save(booking);
            return toData(booking, payment, latestAttempt.getGatewayOrderId(), razorpayGatewayService.configuredKeyId(), amountInPaise(payment.getAmount()));
        }
        RazorpayGatewayService.RazorpayOrderData gatewayOrder = razorpayGatewayService.createOrder(
                payment.getPaymentCode(),
                payment.getAmount(),
                payment.getCurrencyCode()
        );
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        paymentRepository.save(payment);

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(payment.getId());
        attempt.setGatewayName("RAZORPAY");
        attempt.setGatewayOrderId(gatewayOrder.orderId());
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        attempt.setRequestedAmount(payment.getAmount());
        attempt.setResponseCode(gatewayOrder.status());
        attempt.setAttemptedAt(LocalDateTime.now());
        paymentAttemptRepository.save(attempt);

        booking.setPaymentStatus(PayablePaymentStatus.PENDING);
        bookingRepository.save(booking);
        notificationService.notifyUser(
                booking.getUserId(),
                "BOOKING_PAYMENT_PENDING",
                "Complete your payment",
                "Your booking is reserved. Complete payment to confirm it.",
                java.util.Map.of(
                        "bookingId", booking.getId(),
                        "paymentCode", payment.getPaymentCode(),
                        "razorpayOrderId", gatewayOrder.orderId()
                )
        );
        return toData(booking, payment, gatewayOrder.orderId(), gatewayOrder.keyId(), gatewayOrder.amountInPaise());
    }

    private BookingPaymentData initiateForBookingRequest(Long bookingRequestId) {
        BookingRequestEntity bookingRequest = bookingRequestRepository.findById(bookingRequestId)
                .orElseThrow(() -> new BadRequestException("Booking request not found."));
        List<BookingEntity> acceptedBookings = payableRequestBookings(bookingRequestId);
        if (acceptedBookings.isEmpty()) {
            throw new BadRequestException("No accepted labour booking is waiting for payment.");
        }

        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING_REQUEST, bookingRequestId)
                .orElseGet(() -> createRequestPayment(bookingRequest, acceptedBookings));
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS) {
            throw new BadRequestException("Payment is already completed for this group booking request.");
        }
        payment.setAmount(resolveGroupBookingRequestAmount(acceptedBookings));
        paymentRepository.save(payment);
        PaymentAttemptEntity latestAttempt = paymentAttemptRepository
                .findTopByPaymentIdAndGatewayNameOrderByIdDesc(payment.getId(), "RAZORPAY")
                .orElse(null);
        if (isReusablePendingAttempt(latestAttempt)) {
            payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
            paymentRepository.save(payment);
            markRequestBookingsPaymentState(acceptedBookings, PayablePaymentStatus.PENDING);
            return toRequestData(bookingRequest, acceptedBookings, payment, latestAttempt.getGatewayOrderId(), razorpayGatewayService.configuredKeyId(), amountInPaise(payment.getAmount()));
        }
        RazorpayGatewayService.RazorpayOrderData gatewayOrder = razorpayGatewayService.createOrder(
                payment.getPaymentCode(),
                payment.getAmount(),
                payment.getCurrencyCode()
        );
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        paymentRepository.save(payment);

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(payment.getId());
        attempt.setGatewayName("RAZORPAY");
        attempt.setGatewayOrderId(gatewayOrder.orderId());
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        attempt.setRequestedAmount(payment.getAmount());
        attempt.setResponseCode(gatewayOrder.status());
        attempt.setAttemptedAt(LocalDateTime.now());
        paymentAttemptRepository.save(attempt);

        markRequestBookingsPaymentState(acceptedBookings, PayablePaymentStatus.PENDING);
        notificationService.notifyUser(
                bookingRequest.getUserId(),
                "BOOKING_PAYMENT_PENDING",
                "Complete your payment",
                "Your group labour booking is reserved. Complete payment to confirm accepted labour.",
                java.util.Map.of(
                        "requestId", bookingRequest.getId(),
                        "requestCode", bookingRequest.getRequestCode(),
                        "paymentCode", payment.getPaymentCode(),
                        "acceptedCount", acceptedBookings.size()
                )
        );
        return toRequestData(bookingRequest, acceptedBookings, payment, gatewayOrder.orderId(), gatewayOrder.keyId(), gatewayOrder.amountInPaise());
    }

    @Override
    @Transactional
    public BookingPaymentData markSuccess(CompleteBookingPaymentRequest request) {
        BookingEntity booking = loadBooking(request.bookingId());
        PaymentEntity payment = loadPaymentForBooking(request.paymentCode(), booking.getId());
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED) {
            return toData(booking, payment, request.razorpayOrderId(), null, amountInPaise(payment.getAmount()));
        }
        if (booking.getBookingStatus() == BookingLifecycleStatus.CANCELLED
                || payment.getPaymentStatus() == PaymentLifecycleStatus.FAILED
                || booking.getPaymentStatus() == PayablePaymentStatus.FAILED
                || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED) {
            return toData(booking, payment, request.razorpayOrderId(), null, amountInPaise(payment.getAmount()));
        }
        PaymentAttemptEntity attempt = paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc(request.razorpayOrderId())
                .orElseThrow(() -> new BadRequestException("Razorpay payment attempt not found."));
        if (!attempt.getPaymentId().equals(payment.getId())) {
            throw new BadRequestException("Razorpay order does not belong to this payment.");
        }
        if (request.razorpayPaymentId() == null || request.razorpayPaymentId().isBlank()) {
            throw new BadRequestException("Razorpay payment id is required.");
        }
        if (request.razorpaySignature() == null || request.razorpaySignature().isBlank()) {
            throw new BadRequestException("Razorpay signature is required.");
        }
        if (!razorpayGatewayService.verifyPaymentSignature(
                request.razorpayOrderId(),
                request.razorpayPaymentId(),
                request.razorpaySignature()
        )) {
            throw new BadRequestException("Razorpay payment signature verification failed.");
        }
        if (paymentTransactionRepository.findByGatewayTransactionId(request.razorpayPaymentId()).isPresent()) {
            return toData(booking, payment, request.razorpayOrderId(), null, amountInPaise(payment.getAmount()));
        }

        payment.setPaymentStatus(PaymentLifecycleStatus.SUCCESS);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        attempt.setAttemptStatus(PaymentAttemptStatus.SUCCESS);
        attempt.setResponseCode("captured");
        paymentAttemptRepository.save(attempt);

        PaymentTransactionEntity transaction = new PaymentTransactionEntity();
        transaction.setPaymentId(payment.getId());
        transaction.setGatewayTransactionId(request.razorpayPaymentId());
        transaction.setTransactionType(PaymentTransactionType.PAYMENT);
        transaction.setTransactionStatus(PaymentTransactionStatus.SUCCESS);
        transaction.setAmount(payment.getAmount());
        transaction.setProcessedAt(LocalDateTime.now());
        paymentTransactionRepository.save(transaction);

        String oldStatus = booking.getBookingStatus().name();
        booking.setPaymentStatus(PayablePaymentStatus.PAID);
        booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_COMPLETED);
        bookingRepository.save(booking);
        prepareStartWorkOtp(booking);
        bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), booking.getUserId(), "Payment completed");
        notificationService.notifyUser(
                booking.getUserId(),
                "BOOKING_PAYMENT_SUCCESS",
                "Payment successful",
                "Your booking payment was completed successfully.",
                java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
        );
        Long providerUserId = booking.getProviderEntityType().name().equals("LABOUR")
                ? bookingSupportRepository.findLabourUserId(booking.getProviderEntityId()).orElse(null)
                : bookingSupportRepository.findServiceProviderUserId(booking.getProviderEntityId()).orElse(null);
        if (providerUserId != null) {
            notificationService.notifyUser(
                    providerUserId,
                    "BOOKING_PAYMENT_SUCCESS",
                    "Payment received",
                    "User payment is complete. Contact details are now available.",
                    java.util.Map.of(
                            "bookingId", booking.getId(),
                            "bookingCode", booking.getBookingCode(),
                            "appContext", "PROVIDER_APP"
                    )
            );
        }
        return toData(booking, payment, request.razorpayOrderId(), null, amountInPaise(payment.getAmount()));
    }

    @Override
    @Transactional
    public BookingPaymentData markFailure(CompleteBookingPaymentRequest request) {
        BookingEntity booking = loadBooking(request.bookingId());
        PaymentEntity payment = loadPaymentForBooking(request.paymentCode(), booking.getId());
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.FAILED
                && booking.getBookingStatus() == BookingLifecycleStatus.CANCELLED) {
            return toData(booking, payment, request.razorpayOrderId(), null, amountInPaise(payment.getAmount()));
        }
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED
                || booking.getPaymentStatus() == PayablePaymentStatus.PAID
                || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED) {
            return toData(booking, payment, request.razorpayOrderId(), null, amountInPaise(payment.getAmount()));
        }
        PaymentAttemptEntity attempt = paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc(request.razorpayOrderId())
                .orElseThrow(() -> new BadRequestException("Razorpay payment attempt not found."));
        if (!attempt.getPaymentId().equals(payment.getId())) {
            throw new BadRequestException("Razorpay order does not belong to this payment.");
        }
        payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        attempt.setAttemptStatus(PaymentAttemptStatus.FAILED);
        attempt.setResponseCode(
                request.failureCode() != null && !request.failureCode().isBlank()
                        ? request.failureCode()
                        : "failed"
        );
        paymentAttemptRepository.save(attempt);

        String oldStatus = booking.getBookingStatus().name();
        booking.setPaymentStatus(PayablePaymentStatus.FAILED);
        booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
        bookingRepository.save(booking);
        bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), booking.getUserId(), "Payment failed");
        releaseCapacityIfNeeded(booking);
        notificationService.notifyUser(
                booking.getUserId(),
                "BOOKING_PAYMENT_FAILED",
                "Payment failed",
                "Your booking payment failed and the booking has been cancelled.",
                java.util.Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode())
        );
        return toData(booking, payment, request.razorpayOrderId(), null, amountInPaise(payment.getAmount()));
    }

    private PaymentEntity createPayment(BookingEntity booking) {
        PaymentEntity payment = new PaymentEntity();
        payment.setPaymentCode("PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        payment.setPayableType(PayableType.BOOKING);
        payment.setPayableId(booking.getId());
        payment.setPayerUserId(booking.getUserId());
        payment.setPaymentStatus(PaymentLifecycleStatus.INITIATED);
        payment.setAmount(resolvePaymentAmount(booking));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now());
        return paymentRepository.save(payment);
    }

    private PaymentEntity createRequestPayment(BookingRequestEntity bookingRequest, List<BookingEntity> acceptedBookings) {
        PaymentEntity payment = new PaymentEntity();
        payment.setPaymentCode("PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        payment.setPayableType(PayableType.BOOKING_REQUEST);
        payment.setPayableId(bookingRequest.getId());
        payment.setPayerUserId(bookingRequest.getUserId());
        payment.setPaymentStatus(PaymentLifecycleStatus.INITIATED);
        payment.setAmount(resolveGroupBookingRequestAmount(acceptedBookings));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now());
        return paymentRepository.save(payment);
    }

    private BigDecimal resolveGroupBookingRequestAmount(List<BookingEntity> acceptedBookings) {
        BigDecimal totalCharge = BigDecimal.ZERO;
        for (BookingEntity booking : acceptedBookings) {
            totalCharge = totalCharge.add(prepareLabourBookingCharge(booking));
        }
        if (!acceptedBookings.isEmpty()) {
            bookingRepository.saveAll(acceptedBookings);
        }
        return totalCharge;
    }

    private List<BookingEntity> payableRequestBookings(Long bookingRequestId) {
        return bookingRepository.findByBookingRequestIdOrderByIdAsc(bookingRequestId).stream()
                .filter(booking -> booking.getBookingStatus() == BookingLifecycleStatus.PAYMENT_PENDING)
                .filter(booking -> booking.getPaymentStatus() == PayablePaymentStatus.UNPAID
                        || booking.getPaymentStatus() == PayablePaymentStatus.PENDING
                        || booking.getPaymentStatus() == PayablePaymentStatus.FAILED)
                .toList();
    }

    private void markRequestBookingsPaymentState(List<BookingEntity> bookings, PayablePaymentStatus paymentStatus) {
        for (BookingEntity booking : bookings) {
            booking.setPaymentStatus(paymentStatus);
            if (booking.getBookingStatus() == BookingLifecycleStatus.CREATED
                    || booking.getBookingStatus() == BookingLifecycleStatus.ACCEPTED) {
                booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_PENDING);
            }
        }
        bookingRepository.saveAll(bookings);
    }

    private void prepareStartWorkOtp(BookingEntity booking) {
        List<BookingActionOtpEntity> openOtps = bookingActionOtpRepository
                .findByBookingIdAndOtpPurposeAndOtpStatus(
                        booking.getId(),
                        BookingOtpPurpose.START_WORK,
                        BookingActionOtpStatus.GENERATED
                );
        for (BookingActionOtpEntity otp : openOtps) {
            otp.setOtpStatus(BookingActionOtpStatus.CANCELLED);
        }
        if (!openOtps.isEmpty()) {
            bookingActionOtpRepository.saveAll(openOtps);
        }

        BookingActionOtpEntity otp = new BookingActionOtpEntity();
        otp.setBookingId(booking.getId());
        otp.setOtpPurpose(BookingOtpPurpose.START_WORK);
        otp.setOtpCode(String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1_000_000)));
        otp.setIssuedToUserId(booking.getUserId());
        otp.setOtpStatus(BookingActionOtpStatus.GENERATED);
        otp.setExpiresAt(booking.getScheduledStartAt().plusMinutes(bookingPolicyService.noShowAutoCancelMinutes()));
        bookingActionOtpRepository.save(otp);
    }

    private BigDecimal resolvePaymentAmount(BookingEntity booking) {
        BigDecimal subtotal = booking.getSubtotalAmount() == null ? BigDecimal.ZERO : booking.getSubtotalAmount();
        if (booking.getBookingType() == BookingFlowType.LABOUR) {
            BigDecimal bookingCharge = prepareLabourBookingCharge(booking);
            bookingRepository.save(booking);
            return bookingCharge;
        }
        BigDecimal platformFee = bookingPolicyService.servicePlatformFeeAmount(subtotal);
        booking.setPlatformFeeAmount(platformFee);
        booking.setTotalEstimatedAmount(subtotal.add(platformFee));
        bookingRepository.save(booking);
        return booking.getTotalEstimatedAmount();
    }

    private BigDecimal prepareLabourBookingCharge(BookingEntity booking) {
        BigDecimal subtotal = booking.getSubtotalAmount() == null ? BigDecimal.ZERO : booking.getSubtotalAmount();
        BigDecimal bookingCharge = bookingPolicyService.labourBookingChargeAmount(subtotal);
        booking.setPlatformFeeAmount(bookingCharge);
        booking.setTotalEstimatedAmount(subtotal);
        return bookingCharge;
    }

    private BookingEntity loadBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BadRequestException("Booking not found."));
    }

    private PaymentEntity loadPaymentForBooking(String paymentCode, Long bookingId) {
        PaymentEntity payment = paymentRepository.findByPaymentCode(paymentCode)
                .orElseThrow(() -> new BadRequestException("Payment not found."));
        if (payment.getPayableType() != PayableType.BOOKING || !payment.getPayableId().equals(bookingId)) {
            throw new BadRequestException("Payment does not belong to this booking.");
        }
        return payment;
    }

    private void releaseCapacityIfNeeded(BookingEntity booking) {
        if (booking.getProviderEntityType().name().equals("SERVICE_PROVIDER")) {
            bookingSupportRepository.incrementAvailableServiceMen(booking.getProviderEntityId());
        }
    }

    private boolean isReusablePendingAttempt(PaymentAttemptEntity attempt) {
        return attempt != null
                && attempt.getGatewayOrderId() != null
                && !attempt.getGatewayOrderId().isBlank()
                && (attempt.getAttemptStatus() == PaymentAttemptStatus.PENDING
                || attempt.getAttemptStatus() == PaymentAttemptStatus.INITIATED);
    }

    private BookingPaymentData toData(
            BookingEntity booking,
            PaymentEntity payment,
            String razorpayOrderId,
            String razorpayKeyId,
            Long amountInPaise
    ) {
        return new BookingPaymentData(
                booking.getId(),
                booking.getBookingCode(),
                payment.getPaymentCode(),
                "RAZORPAY",
                razorpayKeyId,
                razorpayOrderId,
                payment.getPaymentStatus(),
                booking.getBookingStatus(),
                booking.getPaymentStatus(),
                payment.getAmount(),
                payment.getCurrencyCode(),
                amountInPaise
        );
    }

    private BookingPaymentData toRequestData(
            BookingRequestEntity bookingRequest,
            List<BookingEntity> acceptedBookings,
            PaymentEntity payment,
            String razorpayOrderId,
            String razorpayKeyId,
            Long amountInPaise
    ) {
        BookingEntity referenceBooking = acceptedBookings.isEmpty() ? null : acceptedBookings.getFirst();
        return new BookingPaymentData(
                referenceBooking == null ? bookingRequest.getId() : referenceBooking.getId(),
                referenceBooking == null ? bookingRequest.getRequestCode() : referenceBooking.getBookingCode(),
                payment.getPaymentCode(),
                "RAZORPAY",
                razorpayKeyId,
                razorpayOrderId,
                payment.getPaymentStatus(),
                referenceBooking == null ? BookingLifecycleStatus.PAYMENT_PENDING : referenceBooking.getBookingStatus(),
                referenceBooking == null ? PayablePaymentStatus.PENDING : referenceBooking.getPaymentStatus(),
                payment.getAmount(),
                payment.getCurrencyCode(),
                amountInPaise
        );
    }

    private long amountInPaise(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).longValue();
    }
}
