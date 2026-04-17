package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.UserCancelBookingRequest;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.domain.enums.PenaltyType;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.domain.enums.RefundLifecycleStatus;
import com.msa.booking.payment.modules.settlement.service.SettlementLifecycleService;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.RefundEntity;
import com.msa.booking.payment.persistence.repository.BookingActionOtpRepository;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PenaltyRepository;
import com.msa.booking.payment.persistence.repository.RefundRepository;
import com.msa.booking.payment.persistence.repository.SuspensionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingLifecycleServiceImplTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BookingActionOtpRepository bookingActionOtpRepository;
    @Mock
    private BookingSupportRepository bookingSupportRepository;
    @Mock
    private PenaltyRepository penaltyRepository;
    @Mock
    private SuspensionRepository suspensionRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private BookingPolicyService bookingPolicyService;
    @Mock
    private BookingHistoryService bookingHistoryService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private SettlementLifecycleService settlementLifecycleService;

    private BookingLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookingLifecycleServiceImpl(
                bookingRepository,
                paymentRepository,
                bookingActionOtpRepository,
                bookingSupportRepository,
                penaltyRepository,
                suspensionRepository,
                refundRepository,
                bookingPolicyService,
                bookingHistoryService,
                notificationService,
                settlementLifecycleService
        );

        when(bookingRepository.save(any(BookingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRepository.save(any(RefundEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void cancelByUserReusesExistingSuccessRefundForNoShowCancellation() {
        BookingEntity booking = paymentCompletedBooking();
        PaymentEntity payment = bookingPayment(booking.getId());
        RefundEntity refund = new RefundEntity();
        refund.setId(91L);
        refund.setPaymentId(payment.getId());
        refund.setRefundCode("RFN-OLD");
        refund.setRefundStatus(RefundLifecycleStatus.SUCCESS);
        refund.setRequestedAmount(payment.getAmount());

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING, booking.getId())).thenReturn(Optional.of(payment));
        when(refundRepository.findTopByPaymentIdOrderByIdDesc(payment.getId())).thenReturn(Optional.of(refund));
        when(bookingSupportRepository.findServiceProviderUserId(booking.getProviderEntityId())).thenReturn(Optional.of(99L));
        when(bookingSupportRepository.countPenaltiesSince(eq(99L), eq(PenaltyType.WARNING), eq("NO_SHOW_"), any(LocalDateTime.class))).thenReturn(0L);
        when(bookingPolicyService.serviceDefaultReachTimelineMinutes()).thenReturn(15);
        when(bookingPolicyService.serviceNoShowSuspendThreshold()).thenReturn(3);

        service.cancelByUser(new UserCancelBookingRequest(booking.getId(), "Provider late"));

        assertEquals(BookingLifecycleStatus.CANCELLED, booking.getBookingStatus());
        assertEquals(PayablePaymentStatus.REFUNDED, booking.getPaymentStatus());
        assertEquals(PaymentLifecycleStatus.REFUNDED, payment.getPaymentStatus());

        ArgumentCaptor<RefundEntity> refundCaptor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepository).save(refundCaptor.capture());
        RefundEntity savedRefund = refundCaptor.getValue();
        assertEquals(91L, savedRefund.getId());
        assertEquals(RefundLifecycleStatus.SUCCESS, savedRefund.getRefundStatus());
        assertEquals(payment.getAmount(), savedRefund.getApprovedAmount());
        assertEquals("Provider did not reach in time", savedRefund.getReason());
        verify(bookingSupportRepository).incrementAvailableServiceMen(booking.getProviderEntityId());
        verify(notificationService).notifyUser(
                eq(77L),
                eq("BOOKING_REFUND_SUCCESS"),
                eq("Refund completed"),
                eq("Your refund has been completed for the cancelled booking."),
                any()
        );
    }

    @Test
    void cancelByUserReusesExistingRejectedRefundForPostStartCancellation() {
        BookingEntity booking = inProgressBooking();
        PaymentEntity payment = bookingPayment(booking.getId());
        RefundEntity refund = new RefundEntity();
        refund.setId(92L);
        refund.setPaymentId(payment.getId());
        refund.setRefundCode("RFN-EXISTING");
        refund.setRefundStatus(RefundLifecycleStatus.REJECTED);
        refund.setRequestedAmount(payment.getAmount());

        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findByPayableTypeAndPayableId(PayableType.BOOKING, booking.getId())).thenReturn(Optional.of(payment));
        when(refundRepository.findTopByPaymentIdOrderByIdDesc(payment.getId())).thenReturn(Optional.of(refund));
        when(bookingSupportRepository.findServiceProviderUserId(booking.getProviderEntityId())).thenReturn(Optional.of(99L));
        when(bookingPolicyService.postStartCancellationPenaltyAmount()).thenReturn(BigDecimal.valueOf(149));

        service.cancelByUser(new UserCancelBookingRequest(booking.getId(), "Need to stop"));

        assertEquals(BookingLifecycleStatus.CANCELLED, booking.getBookingStatus());
        assertEquals(PayablePaymentStatus.PAID, booking.getPaymentStatus());
        verify(bookingSupportRepository).incrementAvailableServiceMen(booking.getProviderEntityId());

        ArgumentCaptor<RefundEntity> refundCaptor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepository).save(refundCaptor.capture());
        RefundEntity savedRefund = refundCaptor.getValue();
        assertEquals(92L, savedRefund.getId());
        assertEquals(RefundLifecycleStatus.REJECTED, savedRefund.getRefundStatus());
        assertEquals(BigDecimal.ZERO, savedRefund.getApprovedAmount());
        assertEquals("User cancelled after work started. No refund to user; provider half-share applies offline.", savedRefund.getReason());
        verify(paymentRepository, never()).save(any(PaymentEntity.class));
        verify(notificationService).notifyUser(
                eq(77L),
                eq("BOOKING_REFUND_REJECTED"),
                eq("No refund applicable"),
                eq("This booking cancellation does not qualify for a refund under the current policy."),
                any()
        );
    }

    private BookingEntity paymentCompletedBooking() {
        BookingEntity booking = baseBooking();
        booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_COMPLETED);
        booking.setScheduledStartAt(LocalDateTime.now().minusMinutes(45));
        return booking;
    }

    private BookingEntity inProgressBooking() {
        BookingEntity booking = baseBooking();
        booking.setBookingStatus(BookingLifecycleStatus.IN_PROGRESS);
        booking.setScheduledStartAt(LocalDateTime.now().minusMinutes(10));
        return booking;
    }

    private BookingEntity baseBooking() {
        BookingEntity booking = new BookingEntity();
        booking.setId(10L);
        booking.setBookingCode("BK-1001");
        booking.setBookingType(BookingFlowType.SERVICE);
        booking.setUserId(77L);
        booking.setProviderEntityType(ProviderEntityType.SERVICE_PROVIDER);
        booking.setProviderEntityId(123L);
        booking.setAddressId(55L);
        booking.setPaymentStatus(PayablePaymentStatus.PAID);
        booking.setCurrencyCode("INR");
        return booking;
    }

    private PaymentEntity bookingPayment(Long bookingId) {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(500L);
        payment.setPayableType(PayableType.BOOKING);
        payment.setPayableId(bookingId);
        payment.setPaymentCode("PAY-BOOKING");
        payment.setPaymentStatus(PaymentLifecycleStatus.SUCCESS);
        payment.setAmount(BigDecimal.valueOf(549));
        payment.setCurrencyCode("INR");
        return payment;
    }
}
