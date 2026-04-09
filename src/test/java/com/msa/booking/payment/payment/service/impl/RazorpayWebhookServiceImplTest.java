package com.msa.booking.payment.payment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.OrderEntity;
import com.msa.booking.payment.persistence.entity.OrderItemEntity;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import com.msa.booking.payment.persistence.repository.OrderItemRepository;
import com.msa.booking.payment.persistence.repository.OrderRepository;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import com.msa.booking.payment.persistence.repository.ShopOrderSupportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RazorpayWebhookServiceImplTest {

    @Mock
    private RazorpayGatewayService razorpayGatewayService;
    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private BookingSupportRepository bookingSupportRepository;
    @Mock
    private ShopOrderSupportRepository shopOrderSupportRepository;
    @Mock
    private BookingHistoryService bookingHistoryService;
    @Mock
    private NotificationService notificationService;

    private RazorpayWebhookServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RazorpayWebhookServiceImpl(
                razorpayGatewayService,
                new ObjectMapper(),
                paymentAttemptRepository,
                paymentRepository,
                paymentTransactionRepository,
                bookingRepository,
                orderRepository,
                orderItemRepository,
                bookingSupportRepository,
                shopOrderSupportRepository,
                bookingHistoryService,
                notificationService
        );

        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentAttemptRepository.save(any(PaymentAttemptEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bookingRepository.save(any(BookingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void processWebhookMarksBookingPaymentCaptured() {
        String body = """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_webhook_1",
                        "order_id": "order_webhook_1"
                      }
                    }
                  }
                }
                """;
        PaymentAttemptEntity attempt = paymentAttempt(900L, "order_webhook_1");
        PaymentEntity payment = bookingPayment(900L, 10L);
        BookingEntity booking = booking();

        when(razorpayGatewayService.verifyWebhookSignature(body, "valid_signature")).thenReturn(true);
        when(paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc("order_webhook_1")).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(900L)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByGatewayTransactionId("pay_webhook_1")).thenReturn(Optional.empty());
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));

        service.processWebhook(body, "valid_signature");

        assertEquals(PaymentLifecycleStatus.SUCCESS, payment.getPaymentStatus());
        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getAttemptStatus());
        assertEquals(PayablePaymentStatus.PAID, booking.getPaymentStatus());
        assertEquals(BookingLifecycleStatus.PAYMENT_COMPLETED, booking.getBookingStatus());

        verify(bookingHistoryService).recordBookingStatus(
                eq(booking),
                eq("PAYMENT_PENDING"),
                eq("PAYMENT_COMPLETED"),
                eq(77L),
                eq("Payment completed from Razorpay webhook")
        );
        verify(notificationService).notifyUser(
                eq(77L),
                eq("BOOKING_PAYMENT_SUCCESS"),
                eq("Payment successful"),
                eq("Your booking payment was completed successfully."),
                any(Map.class)
        );
    }

    @Test
    void processWebhookMarksOrderPaymentFailureAndReleasesInventory() {
        String body = """
                {
                  "event": "payment.failed",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_webhook_fail",
                        "order_id": "order_webhook_fail",
                        "error_code": "BAD_CARD"
                      }
                    }
                  }
                }
                """;
        PaymentAttemptEntity attempt = paymentAttempt(901L, "order_webhook_fail");
        PaymentEntity payment = orderPayment(901L, 21L);
        OrderEntity order = order();
        OrderItemEntity item = new OrderItemEntity();
        item.setVariantId(501L);
        item.setQuantity(2);

        when(razorpayGatewayService.verifyWebhookSignature(body, "valid_signature")).thenReturn(true);
        when(paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc("order_webhook_fail")).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(901L)).thenReturn(Optional.of(payment));
        when(orderRepository.findById(21L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(21L)).thenReturn(List.of(item));

        service.processWebhook(body, "valid_signature");

        assertEquals(PaymentLifecycleStatus.FAILED, payment.getPaymentStatus());
        assertEquals(OrderLifecycleStatus.CANCELLED, order.getOrderStatus());
        assertEquals(PayablePaymentStatus.FAILED, order.getPaymentStatus());
        verify(shopOrderSupportRepository).releaseReservedInventory(501L, 2);
        verify(bookingHistoryService).recordOrderStatus(
                eq(order),
                eq("PAYMENT_PENDING"),
                eq("CANCELLED"),
                eq(88L),
                eq("Order payment failed from Razorpay webhook")
        );
    }

    @Test
    void processWebhookRejectsInvalidSignature() {
        when(razorpayGatewayService.verifyWebhookSignature("{}", "bad_signature")).thenReturn(false);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.processWebhook("{}", "bad_signature")
        );

        assertEquals("Invalid Razorpay webhook signature.", exception.getMessage());
        verify(paymentAttemptRepository, never()).findFirstByGatewayOrderIdOrderByAttemptedAtDesc(any());
    }

    private PaymentAttemptEntity paymentAttempt(Long paymentId, String orderId) {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(paymentId);
        attempt.setGatewayName("RAZORPAY");
        attempt.setGatewayOrderId(orderId);
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        attempt.setRequestedAmount(BigDecimal.valueOf(499));
        attempt.setAttemptedAt(LocalDateTime.now().minusMinutes(1));
        return attempt;
    }

    private PaymentEntity bookingPayment(Long paymentId, Long bookingId) {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(paymentId);
        payment.setPayableType(PayableType.BOOKING);
        payment.setPayableId(bookingId);
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(499));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(10));
        return payment;
    }

    private PaymentEntity orderPayment(Long paymentId, Long orderId) {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(paymentId);
        payment.setPayableType(PayableType.ORDER);
        payment.setPayableId(orderId);
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(799));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(10));
        return payment;
    }

    private BookingEntity booking() {
        BookingEntity booking = new BookingEntity();
        booking.setId(10L);
        booking.setBookingCode("BKG-WEBHOOK");
        booking.setUserId(77L);
        booking.setProviderEntityType(ProviderEntityType.LABOUR);
        booking.setProviderEntityId(100L);
        booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_PENDING);
        booking.setPaymentStatus(PayablePaymentStatus.PENDING);
        return booking;
    }

    private OrderEntity order() {
        OrderEntity order = new OrderEntity();
        order.setId(21L);
        order.setOrderCode("ORD-WEBHOOK");
        order.setUserId(88L);
        order.setShopId(300L);
        order.setOrderStatus(OrderLifecycleStatus.PAYMENT_PENDING);
        order.setPaymentStatus(PayablePaymentStatus.PENDING);
        return order;
    }
}
