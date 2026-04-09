package com.msa.booking.payment.order.service.impl;

import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.OrderFulfillmentType;
import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.order.dto.CancelShopOrderRequest;
import com.msa.booking.payment.order.dto.CompleteShopOrderPaymentRequest;
import com.msa.booking.payment.order.dto.CreateShopOrderRequest;
import com.msa.booking.payment.order.dto.ShopOrderItemRequest;
import com.msa.booking.payment.order.projection.ShopCheckoutItemProjection;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.msa.booking.payment.persistence.entity.OrderEntity;
import com.msa.booking.payment.persistence.entity.OrderItemEntity;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.repository.OrderItemRepository;
import com.msa.booking.payment.persistence.repository.OrderRepository;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import com.msa.booking.payment.persistence.repository.RefundRepository;
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
class ShopOrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private ShopOrderSupportRepository shopOrderSupportRepository;
    @Mock
    private BookingPolicyService bookingPolicyService;
    @Mock
    private BookingHistoryService bookingHistoryService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private RazorpayGatewayService razorpayGatewayService;

    private ShopOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShopOrderServiceImpl(
                orderRepository,
                orderItemRepository,
                paymentRepository,
                paymentAttemptRepository,
                paymentTransactionRepository,
                refundRepository,
                shopOrderSupportRepository,
                bookingPolicyService,
                bookingHistoryService,
                notificationService,
                razorpayGatewayService
        );

        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
            OrderEntity order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(21L);
            }
            return order;
        });
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentAttemptRepository.save(any(PaymentAttemptEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderItemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createOrderRejectsItemsFromMultipleShops() {
        CreateShopOrderRequest request = new CreateShopOrderRequest(
                88L,
                999L,
                OrderFulfillmentType.DELIVERY,
                List.of(new ShopOrderItemRequest(1L, 1), new ShopOrderItemRequest(2L, 1))
        );
        when(shopOrderSupportRepository.findCheckoutItemsByVariantIds(any()))
                .thenReturn(List.of(
                        checkoutItem(1L, 101L, 11L),
                        checkoutItem(2L, 102L, 12L)
                ));

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.createOrder(request)
        );

        assertEquals("Items from multiple shops cannot be ordered together. Clear the cart before switching shops.", exception.getMessage());
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    @Test
    void markPaymentSuccessConsumesInventoryAndNotifiesOwner() {
        OrderEntity order = pendingOrder();
        PaymentEntity payment = orderPayment();
        PaymentAttemptEntity attempt = paymentAttempt();
        OrderItemEntity item = new OrderItemEntity();
        item.setVariantId(501L);
        item.setQuantity(2);

        when(orderRepository.findById(21L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByPaymentCode("PAY-ORDER")).thenReturn(Optional.of(payment));
        when(paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc("order_shop_success")).thenReturn(Optional.of(attempt));
        when(razorpayGatewayService.verifyPaymentSignature("order_shop_success", "pay_shop_1", "sig_shop_1")).thenReturn(true);
        when(paymentTransactionRepository.findByGatewayTransactionId("pay_shop_1")).thenReturn(Optional.empty());
        when(orderItemRepository.findByOrderId(21L)).thenReturn(List.of(item));
        when(shopOrderSupportRepository.consumeReservedInventory(501L, 2)).thenReturn(1);
        when(shopOrderSupportRepository.findShopOwnerUserId(300L)).thenReturn(Optional.of(66L));

        var response = service.markPaymentSuccess(new CompleteShopOrderPaymentRequest(
                21L,
                "PAY-ORDER",
                "order_shop_success",
                "pay_shop_1",
                "sig_shop_1",
                null,
                null
        ));

        assertEquals(OrderLifecycleStatus.PAYMENT_COMPLETED, order.getOrderStatus());
        assertEquals(PayablePaymentStatus.PAID, order.getPaymentStatus());
        assertEquals(PaymentLifecycleStatus.SUCCESS, payment.getPaymentStatus());
        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getAttemptStatus());
        assertEquals("order_shop_success", response.razorpayOrderId());

        verify(shopOrderSupportRepository).consumeReservedInventory(501L, 2);
        verify(notificationService).notifyUser(
                eq(88L),
                eq("SHOP_ORDER_PAYMENT_SUCCESS"),
                eq("Order payment successful"),
                eq("Your shop order payment was completed successfully."),
                any(Map.class)
        );
        verify(notificationService).notifyUser(
                eq(66L),
                eq("SHOP_ORDER_RECEIVED"),
                eq("New paid order received"),
                eq("A new paid order is ready for acceptance."),
                any(Map.class)
        );
    }

    @Test
    void cancelByUserRejectsOutForDeliveryOrder() {
        OrderEntity order = pendingOrder();
        order.setOrderStatus(OrderLifecycleStatus.OUT_FOR_DELIVERY);
        when(orderRepository.findById(21L)).thenReturn(Optional.of(order));

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.cancelByUser(new CancelShopOrderRequest(21L, 88L, "Need to cancel"))
        );

        assertEquals("Order cannot be cancelled after it is out for delivery.", exception.getMessage());
        verify(notificationService, never()).notifyUser(eq(88L), eq("SHOP_ORDER_CANCELLED"), any(), any(), any(Map.class));
    }

    private ShopCheckoutItemProjection checkoutItem(Long variantId, Long productId, Long shopId) {
        return new ShopCheckoutItemProjection() {
            @Override
            public Long getVariantId() { return variantId; }
            @Override
            public Long getProductId() { return productId; }
            @Override
            public Long getShopId() { return shopId; }
            @Override
            public Long getShopOwnerUserId() { return 66L; }
            @Override
            public Long getShopLocationId() { return 701L; }
            @Override
            public String getProductName() { return "Item"; }
            @Override
            public String getVariantName() { return "Default"; }
            @Override
            public BigDecimal getSellingPrice() { return BigDecimal.valueOf(100); }
            @Override
            public Boolean getProductActive() { return true; }
            @Override
            public Integer getQuantityAvailable() { return 10; }
            @Override
            public Integer getReservedQuantity() { return 0; }
            @Override
            public String getInventoryStatus() { return "IN_STOCK"; }
        };
    }

    private OrderEntity pendingOrder() {
        OrderEntity order = new OrderEntity();
        order.setId(21L);
        order.setOrderCode("ORD-123");
        order.setUserId(88L);
        order.setShopId(300L);
        order.setShopLocationId(701L);
        order.setAddressId(999L);
        order.setOrderStatus(OrderLifecycleStatus.PAYMENT_PENDING);
        order.setPaymentStatus(PayablePaymentStatus.PENDING);
        order.setFulfillmentType(OrderFulfillmentType.DELIVERY);
        order.setSubtotalAmount(BigDecimal.valueOf(700));
        order.setTaxAmount(BigDecimal.ZERO);
        order.setDeliveryFeeAmount(BigDecimal.ZERO);
        order.setPlatformFeeAmount(BigDecimal.valueOf(20));
        order.setPackagingFeeAmount(BigDecimal.ZERO);
        order.setTipAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalAmount(BigDecimal.valueOf(720));
        order.setCurrencyCode("INR");
        return order;
    }

    private PaymentEntity orderPayment() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(600L);
        payment.setPaymentCode("PAY-ORDER");
        payment.setPayableType(PayableType.ORDER);
        payment.setPayableId(21L);
        payment.setPayerUserId(88L);
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(720));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(10));
        return payment;
    }

    private PaymentAttemptEntity paymentAttempt() {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(600L);
        attempt.setGatewayName("RAZORPAY");
        attempt.setGatewayOrderId("order_shop_success");
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        attempt.setRequestedAmount(BigDecimal.valueOf(720));
        attempt.setAttemptedAt(LocalDateTime.now().minusMinutes(1));
        return attempt;
    }
}
