package com.msa.booking.payment.modules.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.config.RazorpayProperties;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.WebhookAcknowledgeResponse;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentInitiateRequest;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentInitiateResponse;
import com.msa.booking.payment.modules.settlement.service.SettlementLifecycleService;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestRepository;
import com.msa.booking.payment.persistence.repository.OrderItemRepository;
import com.msa.booking.payment.persistence.repository.OrderRepository;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import com.msa.booking.payment.persistence.repository.ShopOrderSupportRepository;
import com.msa.booking.payment.persistence.entity.OrderEntity;
import com.msa.booking.payment.persistence.entity.OrderItemEntity;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.OrderFulfillmentType;
import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentLifecycleServiceTest {
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ShopOrderSupportRepository shopOrderSupportRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingRequestRepository bookingRequestRepository;
    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private RazorpayProperties razorpayProperties;
    @Mock
    private RazorpaySignatureService razorpaySignatureService;
    @Mock
    private PaymentWebhookEventService paymentWebhookEventService;
    @Mock
    private SettlementLifecycleService settlementLifecycleService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private BookingPolicyService bookingPolicyService;

    private PaymentLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new PaymentLifecycleService(
                paymentRepository,
                paymentAttemptRepository,
                paymentTransactionRepository,
                orderRepository,
                orderItemRepository,
                shopOrderSupportRepository,
                bookingRepository,
                bookingRequestRepository,
                jdbcTemplate,
                razorpayProperties,
                razorpaySignatureService,
                paymentWebhookEventService,
                settlementLifecycleService,
                notificationService,
                bookingPolicyService,
                new ObjectMapper()
        ) {
            @Override
            protected String createRazorpayOrder(PaymentEntity payment) {
                return "order_retry_test";
            }
        };
    }

    @Test
    void handleRazorpayWebhookIgnoresDuplicateEvent() {
        String body = """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_dup_1",
                        "order_id": "order_dup_1"
                      }
                    }
                  }
                }
                """;
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(700L);
        attempt.setGatewayOrderId("order_dup_1");
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        attempt.setRequestedAmount(BigDecimal.valueOf(199));
        attempt.setAttemptedAt(LocalDateTime.now().minusMinutes(1));

        PaymentEntity payment = new PaymentEntity();
        payment.setId(700L);
        payment.setPayableId(44L);
        payment.setPayableType(PayableType.ORDER);
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(199));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(3));

        when(paymentWebhookEventService.resolveWebhookKey("evt_dup", body)).thenReturn("evt_dup");
        when(paymentAttemptRepository.findTopByGatewayOrderIdOrderByIdDesc("order_dup_1")).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(700L)).thenReturn(Optional.of(payment));
        when(paymentWebhookEventService.registerIfFirst(any(), any(), any(), any(), any(), any())).thenReturn(false);

        WebhookAcknowledgeResponse response = service.handleRazorpayWebhook(body, "signature", "evt_dup");

        assertEquals(false, response.processed());
        assertEquals("Ignored: duplicate webhook event", response.message());
        verify(paymentTransactionRepository, never()).save(any());
        verify(paymentAttemptRepository, never()).save(any(PaymentAttemptEntity.class));
        verify(paymentRepository, never()).save(any(PaymentEntity.class));
    }

    @Test
    void initiateReopensFailedOrderAndReReservesInventory() {
        PaymentEntity payment = payment();
        payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);
        payment.setCompletedAt(LocalDateTime.now().minusMinutes(1));

        OrderEntity order = failedCancelledOrder();
        OrderItemEntity item = new OrderItemEntity();
        item.setVariantId(501L);
        item.setQuantity(2);

        when(paymentRepository.findByPaymentCode("PAY-ORDER")).thenReturn(Optional.of(payment));
        when(orderRepository.findById(44L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(44L)).thenReturn(List.of(item));
        when(shopOrderSupportRepository.reserveInventory(501L, 2)).thenReturn(1);
        when(razorpayProperties.isEnabled()).thenReturn(true);
        when(razorpayProperties.getKeyId()).thenReturn("rzp_test_key");
        when(razorpayProperties.getKeySecret()).thenReturn("rzp_test_secret");
        when(paymentAttemptRepository.findTopByPaymentIdAndGatewayNameOrderByIdDesc(700L, "RAZORPAY"))
                .thenReturn(Optional.empty());

        PaymentInitiateResponse response = service.initiate(88L, "PAY-ORDER", new PaymentInitiateRequest("RAZORPAY"));

        assertEquals("PENDING", response.paymentStatus());
        assertEquals(OrderLifecycleStatus.PAYMENT_PENDING, order.getOrderStatus());
        assertEquals(PayablePaymentStatus.PENDING, order.getPaymentStatus());
        assertEquals(PaymentLifecycleStatus.PENDING, payment.getPaymentStatus());
        verify(shopOrderSupportRepository).reserveInventory(501L, 2);
        verify(notificationService).notifyUser(
                eq(88L),
                eq("SHOP_ORDER_PAYMENT_PENDING"),
                eq("Complete your payment"),
                eq("Your shop order payment is waiting for completion."),
                any()
        );
    }

    @Test
    void initiateRetryFailsWhenInventoryCannotBeReservedAgain() {
        PaymentEntity payment = payment();
        payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);

        OrderEntity order = failedCancelledOrder();
        OrderItemEntity item = new OrderItemEntity();
        item.setVariantId(501L);
        item.setQuantity(2);

        when(paymentRepository.findByPaymentCode("PAY-ORDER")).thenReturn(Optional.of(payment));
        when(orderRepository.findById(44L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(44L)).thenReturn(List.of(item));
        when(razorpayProperties.isEnabled()).thenReturn(true);
        when(razorpayProperties.getKeyId()).thenReturn("rzp_test_key");
        when(razorpayProperties.getKeySecret()).thenReturn("rzp_test_secret");
        when(shopOrderSupportRepository.reserveInventory(501L, 2)).thenReturn(0);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.initiate(88L, "PAY-ORDER", new PaymentInitiateRequest("RAZORPAY"))
        );

        assertEquals(
                "One or more items are no longer available to retry payment for order ORD-FAILED.",
                exception.getMessage()
        );
    }

    @Test
    void failureNotifiesUserForOrderPayment() {
        PaymentEntity payment = payment();
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setId(701L);
        attempt.setPaymentId(700L);
        attempt.setGatewayOrderId("order_fail_1");
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);

        OrderEntity order = failedCancelledOrder();
        order.setOrderStatus(OrderLifecycleStatus.PAYMENT_PENDING);
        order.setPaymentStatus(PayablePaymentStatus.PENDING);

        when(paymentRepository.findByPaymentCode("PAY-ORDER")).thenReturn(Optional.of(payment));
        when(paymentAttemptRepository.findTopByGatewayOrderIdOrderByIdDesc("order_fail_1")).thenReturn(Optional.of(attempt));
        when(orderRepository.findById(44L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(44L)).thenReturn(List.of());

        service.failure(
                88L,
                "PAY-ORDER",
                new com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentFailureRequest(
                        "order_fail_1",
                        "FAILED",
                        "Payment was not completed."
                )
        );

        verify(notificationService).notifyUser(
                eq(88L),
                eq("SHOP_ORDER_PAYMENT_FAILED"),
                eq("Payment not completed"),
                eq("Your shop order payment could not be completed."),
                any()
        );
    }

    @Test
    void verifyNotifiesUserForBookingPaymentSuccess() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(800L);
        payment.setPaymentCode("PAY-BOOKING");
        payment.setPayableId(55L);
        payment.setPayableType(PayableType.BOOKING);
        payment.setPayerUserId(99L);
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(499));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(5));

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setId(801L);
        attempt.setPaymentId(800L);
        attempt.setGatewayOrderId("order_booking_1");
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);

        BookingEntity booking = new BookingEntity();
        booking.setId(55L);
        booking.setBookingCode("BKG-55");
        booking.setUserId(99L);
        booking.setBookingStatus(com.msa.booking.payment.domain.enums.BookingLifecycleStatus.PAYMENT_PENDING);
        booking.setPaymentStatus(PayablePaymentStatus.PENDING);

        when(paymentRepository.findByPaymentCode("PAY-BOOKING")).thenReturn(Optional.of(payment));
        when(paymentAttemptRepository.findTopByGatewayOrderIdOrderByIdDesc("order_booking_1")).thenReturn(Optional.of(attempt));
        when(bookingRepository.findById(55L)).thenReturn(Optional.of(booking));

        service.verify(
                99L,
                "PAY-BOOKING",
                new com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentVerifyRequest(
                        "order_booking_1",
                        "pay_123",
                        "sig_123"
                )
        );

        verify(notificationService).notifyUser(
                eq(99L),
                eq("BOOKING_PAYMENT_SUCCESS"),
                eq("Payment successful"),
                eq("Your booking payment was completed successfully."),
                any()
        );
        verify(settlementLifecycleService).recordSuccessfulPayment(payment);
    }

    private PaymentEntity payment() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(700L);
        payment.setPaymentCode("PAY-ORDER");
        payment.setPayableId(44L);
        payment.setPayableType(PayableType.ORDER);
        payment.setPayerUserId(88L);
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(199));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(3));
        return payment;
    }

    private OrderEntity failedCancelledOrder() {
        OrderEntity order = new OrderEntity();
        order.setId(44L);
        order.setOrderCode("ORD-FAILED");
        order.setUserId(88L);
        order.setShopId(300L);
        order.setShopLocationId(701L);
        order.setAddressId(999L);
        order.setOrderStatus(OrderLifecycleStatus.CANCELLED);
        order.setPaymentStatus(PayablePaymentStatus.FAILED);
        order.setFulfillmentType(OrderFulfillmentType.DELIVERY);
        order.setSubtotalAmount(BigDecimal.valueOf(180));
        order.setTaxAmount(BigDecimal.ZERO);
        order.setDeliveryFeeAmount(BigDecimal.ZERO);
        order.setPlatformFeeAmount(BigDecimal.valueOf(19));
        order.setPackagingFeeAmount(BigDecimal.ZERO);
        order.setTipAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalAmount(BigDecimal.valueOf(199));
        order.setCurrencyCode("INR");
        return order;
    }
}
