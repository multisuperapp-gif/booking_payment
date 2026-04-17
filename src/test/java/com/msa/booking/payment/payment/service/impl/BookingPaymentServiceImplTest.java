package com.msa.booking.payment.payment.service.impl;

import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.payment.dto.CompleteBookingPaymentRequest;
import com.msa.booking.payment.payment.dto.InitiateBookingPaymentRequest;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import com.msa.booking.payment.persistence.repository.BookingActionOtpRepository;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingPaymentServiceImplTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private BookingSupportRepository bookingSupportRepository;
    @Mock
    private BookingActionOtpRepository bookingActionOtpRepository;
    @Mock
    private BookingPolicyService bookingPolicyService;
    @Mock
    private BookingHistoryService bookingHistoryService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private RazorpayGatewayService razorpayGatewayService;

    private BookingPaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookingPaymentServiceImpl(
                bookingRepository,
                paymentRepository,
                paymentAttemptRepository,
                paymentTransactionRepository,
                bookingSupportRepository,
                bookingActionOtpRepository,
                bookingPolicyService,
                bookingHistoryService,
                notificationService,
                razorpayGatewayService
        );

        when(bookingRepository.save(any(BookingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation -> {
            PaymentEntity payment = invocation.getArgument(0);
            if (payment.getId() == null) {
                payment.setId(500L);
            }
            return payment;
        });
        when(paymentAttemptRepository.save(any(PaymentAttemptEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void initiateCreatesRazorpayAttemptAndMarksBookingPending() {
        BookingEntity booking = serviceBooking();
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING, 10L)).thenReturn(Optional.empty());
        when(bookingPolicyService.servicePlatformFee()).thenReturn(BigDecimal.valueOf(49));
        when(razorpayGatewayService.createOrder(any(), eq(BigDecimal.valueOf(549)), eq("INR")))
                .thenReturn(new RazorpayGatewayService.RazorpayOrderData(
                        "rzp_test_key",
                        "order_123",
                        "INR",
                        BigDecimal.valueOf(549),
                        54900L,
                        "created"
                ));

        var response = service.initiate(new InitiateBookingPaymentRequest(10L));

        assertEquals("order_123", response.razorpayOrderId());
        assertEquals("rzp_test_key", response.razorpayKeyId());
        assertEquals(PaymentLifecycleStatus.PENDING, response.paymentLifecycleStatus());
        assertEquals(PayablePaymentStatus.PENDING, booking.getPaymentStatus());
        assertEquals(BigDecimal.valueOf(49), booking.getPlatformFeeAmount());
        assertEquals(BigDecimal.valueOf(549), booking.getTotalEstimatedAmount());

        ArgumentCaptor<PaymentAttemptEntity> attemptCaptor = ArgumentCaptor.forClass(PaymentAttemptEntity.class);
        verify(paymentAttemptRepository).save(attemptCaptor.capture());
        PaymentAttemptEntity savedAttempt = attemptCaptor.getValue();
        assertEquals("RAZORPAY", savedAttempt.getGatewayName());
        assertEquals("order_123", savedAttempt.getGatewayOrderId());
        assertEquals(PaymentAttemptStatus.PENDING, savedAttempt.getAttemptStatus());

        verify(notificationService).notifyUser(
                eq(77L),
                eq("BOOKING_PAYMENT_PENDING"),
                eq("Complete your payment"),
                eq("Your booking is reserved. Complete payment to confirm it."),
                any(Map.class)
        );
        verify(bookingHistoryService, never()).recordBookingStatus(any(), any(), any(), any(), any());
    }

    @Test
    void initiateReusesPendingGatewayAttemptWithoutCreatingNewOrder() {
        BookingEntity booking = serviceBooking();
        PaymentEntity payment = pendingBookingPayment();
        PaymentAttemptEntity attempt = paymentAttempt(payment.getId(), "order_reuse");
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);

        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING, 10L)).thenReturn(Optional.of(payment));
        when(paymentAttemptRepository.findTopByPaymentIdAndGatewayNameOrderByIdDesc(500L, "RAZORPAY"))
                .thenReturn(Optional.of(attempt));
        when(razorpayGatewayService.configuredKeyId()).thenReturn("rzp_live_reuse");

        var response = service.initiate(new InitiateBookingPaymentRequest(10L));

        assertEquals("order_reuse", response.razorpayOrderId());
        assertEquals("rzp_live_reuse", response.razorpayKeyId());
        assertEquals(PaymentLifecycleStatus.PENDING, response.paymentLifecycleStatus());
        verify(razorpayGatewayService, never()).createOrder(any(), any(), any());
        verify(paymentAttemptRepository, never()).save(any(PaymentAttemptEntity.class));
        verify(notificationService, never()).notifyUser(eq(77L), eq("BOOKING_PAYMENT_PENDING"), any(), any(), any(Map.class));
    }

    @Test
    void markSuccessCompletesPaymentAndNotifiesUserAndProvider() {
        BookingEntity booking = serviceBooking();
        PaymentEntity payment = pendingBookingPayment();
        PaymentAttemptEntity attempt = paymentAttempt(payment.getId(), "order_success");

        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByPaymentCode("PAY-BOOKING")).thenReturn(Optional.of(payment));
        when(paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc("order_success")).thenReturn(Optional.of(attempt));
        when(razorpayGatewayService.verifyPaymentSignature("order_success", "pay_123", "sig_123")).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionId("pay_123")).thenReturn(Optional.empty());
        when(bookingSupportRepository.findServiceProviderUserId(booking.getProviderEntityId())).thenReturn(Optional.of(99L));

        var response = service.markSuccess(new CompleteBookingPaymentRequest(
                10L,
                "PAY-BOOKING",
                "order_success",
                "pay_123",
                "sig_123",
                null,
                null
        ));

        assertEquals(BookingLifecycleStatus.PAYMENT_COMPLETED, booking.getBookingStatus());
        assertEquals(PayablePaymentStatus.PAID, booking.getPaymentStatus());
        assertEquals(PaymentLifecycleStatus.SUCCESS, payment.getPaymentStatus());
        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getAttemptStatus());
        assertEquals(PaymentLifecycleStatus.SUCCESS, response.paymentLifecycleStatus());

        verify(bookingHistoryService).recordBookingStatus(
                eq(booking),
                eq("PAYMENT_PENDING"),
                eq("PAYMENT_COMPLETED"),
                eq(77L),
                eq("Payment completed")
        );
        verify(notificationService).notifyUser(
                eq(77L),
                eq("BOOKING_PAYMENT_SUCCESS"),
                eq("Payment successful"),
                eq("Your booking payment was completed successfully."),
                any(Map.class)
        );
        verify(notificationService).notifyUser(
                eq(99L),
                eq("BOOKING_PAYMENT_SUCCESS"),
                eq("Payment received"),
                eq("User payment is complete. Contact details are now available."),
                any(Map.class)
        );
    }

    @Test
    void markFailureCancelsBookingAndReleasesServiceCapacity() {
        BookingEntity booking = serviceBooking();
        PaymentEntity payment = pendingBookingPayment();
        PaymentAttemptEntity attempt = paymentAttempt(payment.getId(), "order_failed");

        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByPaymentCode("PAY-BOOKING")).thenReturn(Optional.of(payment));
        when(paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc("order_failed")).thenReturn(Optional.of(attempt));

        var response = service.markFailure(new CompleteBookingPaymentRequest(
                10L,
                "PAY-BOOKING",
                "order_failed",
                null,
                null,
                "payment_failed",
                "Customer closed checkout"
        ));

        assertEquals(BookingLifecycleStatus.CANCELLED, booking.getBookingStatus());
        assertEquals(PayablePaymentStatus.FAILED, booking.getPaymentStatus());
        assertEquals(PaymentLifecycleStatus.FAILED, payment.getPaymentStatus());
        assertEquals(PaymentAttemptStatus.FAILED, attempt.getAttemptStatus());
        assertEquals(PaymentLifecycleStatus.FAILED, response.paymentLifecycleStatus());

        verify(bookingSupportRepository).incrementAvailableServiceMen(123L);
        verify(bookingHistoryService).recordBookingStatus(
                eq(booking),
                eq("PAYMENT_PENDING"),
                eq("CANCELLED"),
                eq(77L),
                eq("Payment failed")
        );
    }

    @Test
    void markSuccessIgnoresLateSuccessForCancelledBooking() {
        BookingEntity booking = serviceBooking();
        booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
        booking.setPaymentStatus(PayablePaymentStatus.FAILED);
        PaymentEntity payment = pendingBookingPayment();
        payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);

        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByPaymentCode("PAY-BOOKING")).thenReturn(Optional.of(payment));

        var response = service.markSuccess(new CompleteBookingPaymentRequest(
                10L,
                "PAY-BOOKING",
                "order_late_success",
                "pay_late_success",
                "sig_late_success",
                null,
                null
        ));

        assertEquals(BookingLifecycleStatus.CANCELLED, response.bookingStatus());
        assertEquals(PayablePaymentStatus.FAILED, response.payablePaymentStatus());
        verify(paymentAttemptRepository, never()).findFirstByGatewayOrderIdOrderByAttemptedAtDesc(any());
        verify(paymentTransactionRepository, never()).findByGatewayTransactionId(any());
    }

    @Test
    void markFailureIgnoresLateFailureForPaidBooking() {
        BookingEntity booking = serviceBooking();
        booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_COMPLETED);
        booking.setPaymentStatus(PayablePaymentStatus.PAID);
        PaymentEntity payment = pendingBookingPayment();
        payment.setPaymentStatus(PaymentLifecycleStatus.SUCCESS);

        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByPaymentCode("PAY-BOOKING")).thenReturn(Optional.of(payment));

        var response = service.markFailure(new CompleteBookingPaymentRequest(
                10L,
                "PAY-BOOKING",
                "order_late_failure",
                null,
                null,
                "payment_failed",
                "late failure"
        ));

        assertEquals(BookingLifecycleStatus.PAYMENT_COMPLETED, response.bookingStatus());
        assertEquals(PayablePaymentStatus.PAID, response.payablePaymentStatus());
        verify(paymentAttemptRepository, never()).findFirstByGatewayOrderIdOrderByAttemptedAtDesc(any());
        verify(bookingSupportRepository, never()).incrementAvailableServiceMen(any());
    }

    @Test
    void markSuccessRejectsInvalidSignature() {
        BookingEntity booking = serviceBooking();
        PaymentEntity payment = pendingBookingPayment();
        PaymentAttemptEntity attempt = paymentAttempt(payment.getId(), "order_invalid");

        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByPaymentCode("PAY-BOOKING")).thenReturn(Optional.of(payment));
        when(paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc("order_invalid")).thenReturn(Optional.of(attempt));
        when(razorpayGatewayService.verifyPaymentSignature("order_invalid", "pay_invalid", "sig_invalid")).thenReturn(false);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.markSuccess(new CompleteBookingPaymentRequest(
                        10L,
                        "PAY-BOOKING",
                        "order_invalid",
                        "pay_invalid",
                        "sig_invalid",
                        null,
                        null
                ))
        );

        assertEquals("Razorpay payment signature verification failed.", exception.getMessage());
        assertEquals(BookingLifecycleStatus.PAYMENT_PENDING, booking.getBookingStatus());
        assertNull(payment.getCompletedAt());
        verify(notificationService, never()).notifyUser(eq(77L), eq("BOOKING_PAYMENT_SUCCESS"), any(), any(), any(Map.class));
    }

    private BookingEntity serviceBooking() {
        BookingEntity booking = new BookingEntity();
        booking.setId(10L);
        booking.setBookingCode("BKG-123");
        booking.setBookingType(BookingFlowType.SERVICE);
        booking.setUserId(77L);
        booking.setProviderEntityType(ProviderEntityType.SERVICE_PROVIDER);
        booking.setProviderEntityId(123L);
        booking.setAddressId(55L);
        booking.setScheduledStartAt(LocalDateTime.now().plusHours(2));
        booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_PENDING);
        booking.setPaymentStatus(PayablePaymentStatus.UNPAID);
        booking.setSubtotalAmount(BigDecimal.valueOf(500));
        booking.setTaxAmount(BigDecimal.ZERO);
        booking.setDiscountAmount(BigDecimal.ZERO);
        booking.setPlatformFeeAmount(BigDecimal.ZERO);
        booking.setCurrencyCode("INR");
        return booking;
    }

    private PaymentEntity pendingBookingPayment() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(500L);
        payment.setPaymentCode("PAY-BOOKING");
        payment.setPayableType(PayableType.BOOKING);
        payment.setPayableId(10L);
        payment.setPayerUserId(77L);
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(549));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(5));
        return payment;
    }

    private PaymentAttemptEntity paymentAttempt(Long paymentId, String orderId) {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(paymentId);
        attempt.setGatewayName("RAZORPAY");
        attempt.setGatewayOrderId(orderId);
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        attempt.setRequestedAmount(BigDecimal.valueOf(549));
        attempt.setAttemptedAt(LocalDateTime.now().minusMinutes(1));
        return attempt;
    }
}
