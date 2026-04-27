package com.msa.booking.payment.payment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.modules.payment.service.PaymentWebhookEventService;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.order.service.ShopOrderFinanceContextService;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import com.msa.booking.payment.persistence.repository.ShopOrderSupportRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
    private BookingSupportRepository bookingSupportRepository;
    @Mock
    private ShopOrderSupportRepository shopOrderSupportRepository;
    @Mock
    private BookingHistoryService bookingHistoryService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PaymentWebhookEventService paymentWebhookEventService;
    @Mock
    private ShopOrderFinanceContextService shopOrderFinanceContextService;

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
                bookingSupportRepository,
                shopOrderSupportRepository,
                bookingHistoryService,
                notificationService,
                paymentWebhookEventService,
                shopOrderFinanceContextService
        );

        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentAttemptRepository.save(any(PaymentAttemptEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentWebhookEventService.resolveWebhookKey(any(), any())).thenReturn("webhook_key");
        when(paymentWebhookEventService.registerIfFirst(any(), any(), any(), any(), any(), any())).thenReturn(true);
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

        service.processWebhook(body, "valid_signature", "evt_1");

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
    }

    @Test
    void processWebhookMarksShopOrderPaymentFailureAndReleasesInventory() {
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
        PaymentEntity payment = shopOrderPayment(901L, 21L);

        when(razorpayGatewayService.verifyWebhookSignature(body, "valid_signature")).thenReturn(true);
        when(paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc("order_webhook_fail")).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(901L)).thenReturn(Optional.of(payment));
        when(shopOrderFinanceContextService.loadRequired(21L)).thenReturn(orderContext());
        when(shopOrderFinanceContextService.loadItemsRequired(21L)).thenReturn(List.of(
                new com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderItemData(1001L, 501L, 2)
        ));

        service.processWebhook(body, "valid_signature", "evt_2");

        assertEquals(PaymentLifecycleStatus.FAILED, payment.getPaymentStatus());
        assertEquals(PaymentAttemptStatus.FAILED, attempt.getAttemptStatus());
        verify(shopOrderSupportRepository).releaseReservedInventory(501L, 2);
        verify(shopOrderFinanceContextService).updateStateRequired(
                21L,
                PayablePaymentStatus.FAILED.name(),
                "CANCELLED",
                88L,
                "Order payment failed from Razorpay webhook",
                null
        );
        verify(notificationService).notifyUser(
                eq(88L),
                eq("SHOP_ORDER_PAYMENT_FAILED"),
                eq("Order payment failed"),
                eq("Your shop order payment failed and the order was cancelled."),
                any(Map.class)
        );
    }

    private PaymentAttemptEntity paymentAttempt(Long paymentId, String gatewayOrderId) {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(paymentId);
        attempt.setGatewayOrderId(gatewayOrderId);
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        attempt.setRequestedAmount(BigDecimal.valueOf(199));
        attempt.setAttemptedAt(LocalDateTime.now().minusMinutes(1));
        return attempt;
    }

    private PaymentEntity bookingPayment(Long paymentId, Long bookingId) {
        PaymentEntity payment = basePayment(paymentId, bookingId);
        payment.setPayableType(PayableType.BOOKING);
        return payment;
    }

    private PaymentEntity shopOrderPayment(Long paymentId, Long orderId) {
        PaymentEntity payment = basePayment(paymentId, orderId);
        payment.setPayableType(PayableType.SHOP_ORDER);
        return payment;
    }

    private PaymentEntity basePayment(Long paymentId, Long payableId) {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(paymentId);
        payment.setPayableId(payableId);
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(199));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(3));
        payment.setPayerUserId(88L);
        payment.setPaymentCode("PAY-" + payableId);
        return payment;
    }

    private BookingEntity booking() {
        BookingEntity booking = new BookingEntity();
        booking.setId(10L);
        booking.setUserId(77L);
        booking.setBookingCode("BKG-10");
        booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_PENDING);
        booking.setPaymentStatus(PayablePaymentStatus.PENDING);
        return booking;
    }

    private com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderFinanceContextData orderContext() {
        return new com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderFinanceContextData(
                21L,
                "ORD-21",
                300L,
                88L,
                "PAYMENT_PENDING",
                "PENDING",
                BigDecimal.valueOf(150),
                BigDecimal.valueOf(49),
                BigDecimal.valueOf(199),
                BigDecimal.valueOf(19),
                "INR"
        );
    }
}
