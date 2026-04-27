package com.msa.booking.payment.order.service.impl;

import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.OrderFulfillmentType;
import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.integration.shoporders.ShopOrdersRuntimeSyncClient;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.CreatedOrderData;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.modules.settlement.service.SettlementLifecycleService;
import com.msa.booking.payment.order.dto.CancelShopOrderRequest;
import com.msa.booking.payment.order.dto.CreateShopOrderRequest;
import com.msa.booking.payment.order.dto.InitiateShopOrderPaymentRequest;
import com.msa.booking.payment.order.dto.ShopOrderItemRequest;
import com.msa.booking.payment.order.service.ShopOrderFinanceContextService;
import com.msa.booking.payment.order.service.ShopOrdersRuntimeSyncService;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import com.msa.booking.payment.persistence.repository.RefundRepository;
import com.msa.booking.payment.persistence.repository.ShopOrderSupportRepository;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.msa.booking.payment.storage.BillingDocumentLink;
import com.msa.booking.payment.storage.BillingDocumentStorageService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopOrderServiceImplTest {

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
    private NotificationService notificationService;
    @Mock
    private RazorpayGatewayService razorpayGatewayService;
    @Mock
    private SettlementLifecycleService settlementLifecycleService;
    @Mock
    private ShopOrderFinanceContextService shopOrderFinanceContextService;
    @Mock
    private ShopOrdersRuntimeSyncService shopOrdersRuntimeSyncService;
    @Mock
    private ShopOrdersRuntimeSyncClient shopOrdersRuntimeSyncClient;
    @Mock
    private BillingDocumentStorageService billingDocumentStorageService;

    private ShopOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShopOrderServiceImpl(
                paymentRepository,
                paymentAttemptRepository,
                paymentTransactionRepository,
                refundRepository,
                shopOrderSupportRepository,
                notificationService,
                razorpayGatewayService,
                settlementLifecycleService,
                shopOrderFinanceContextService,
                shopOrdersRuntimeSyncService,
                shopOrdersRuntimeSyncClient,
                billingDocumentStorageService
        );
    }

    @Test
    void createOrderDelegatesToRuntimeSyncAndNotifiesUser() {
        CreateShopOrderRequest request = new CreateShopOrderRequest(
                88L,
                999L,
                OrderFulfillmentType.DELIVERY,
                List.of(new ShopOrderItemRequest(1L, 1))
        );

        CreatedOrderData createdOrder = new CreatedOrderData(
                21L,
                "ORD-21",
                300L,
                88L,
                "CREATED",
                "UNPAID",
                BigDecimal.valueOf(150),
                BigDecimal.valueOf(49),
                BigDecimal.valueOf(199),
                BigDecimal.valueOf(19),
                "INR",
                List.of()
        );
        when(shopOrdersRuntimeSyncClient.createOrder(any())).thenReturn(com.msa.booking.payment.common.api.ApiResponse.ok(createdOrder));

        var response = service.createOrder(request);

        assertEquals(21L, response.orderId());
        assertEquals("Order created and inventory reserved.", response.note());
        verify(notificationService).notifyUser(
                eq(88L),
                eq("SHOP_ORDER_PAYMENT_PENDING"),
                eq("Complete your shop order payment"),
                eq("Your order is ready. Complete payment to confirm it."),
                any(Map.class)
        );
    }

    @Test
    void initiatePaymentReusesPendingGatewayAttemptWithoutCreatingNewGatewayOrder() {
        var order = orderContext("PAYMENT_PENDING", "PENDING");
        PaymentEntity payment = orderPayment();
        PaymentAttemptEntity attempt = paymentAttempt();
        attempt.setGatewayOrderId("order_reuse_shop");
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);

        when(shopOrderFinanceContextService.loadRequired(21L)).thenReturn(order);
        when(paymentRepository.findByPayableTypeAndPayableId(PayableType.SHOP_ORDER, 21L)).thenReturn(Optional.of(payment));
        when(paymentAttemptRepository.findTopByPaymentIdAndGatewayNameOrderByIdDesc(600L, "RAZORPAY"))
                .thenReturn(Optional.of(attempt));
        when(razorpayGatewayService.configuredKeyId()).thenReturn("rzp_shop_key");
        when(billingDocumentStorageService.resolvePaymentInvoiceLink(any(), any(), any()))
                .thenReturn(new BillingDocumentLink(null, null));

        var response = service.initiatePayment(new InitiateShopOrderPaymentRequest(21L));

        assertEquals("order_reuse_shop", response.razorpayOrderId());
        assertEquals("Payment already initiated for shop order.", response.note());
        verify(razorpayGatewayService, never()).createOrder(any(), any(), any());
    }

    @Test
    void cancelByUserRejectsOutForDeliveryOrder() {
        when(shopOrderFinanceContextService.loadRequired(21L)).thenReturn(orderContext("OUT_FOR_DELIVERY", "PENDING"));

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.cancelByUser(new CancelShopOrderRequest(21L, 88L, "Need to cancel"))
        );

        assertEquals("Order cannot be cancelled after it is out for delivery.", exception.getMessage());
        verify(notificationService, never()).notifyUser(eq(88L), eq("SHOP_ORDER_CANCELLED"), any(), any(), any(Map.class));
    }

    private ShopOrdersRuntimeSyncDtos.OrderFinanceContextData orderContext(String orderStatus, String paymentStatus) {
        return new ShopOrdersRuntimeSyncDtos.OrderFinanceContextData(
                21L,
                "ORD-21",
                300L,
                88L,
                orderStatus,
                paymentStatus,
                BigDecimal.valueOf(150),
                BigDecimal.valueOf(49),
                BigDecimal.valueOf(199),
                BigDecimal.valueOf(19),
                "INR"
        );
    }

    private PaymentEntity orderPayment() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(600L);
        payment.setPayableType(PayableType.SHOP_ORDER);
        payment.setPayableId(21L);
        payment.setPaymentCode("PAY-ORDER");
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(199));
        payment.setCurrencyCode("INR");
        payment.setPayerUserId(88L);
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(10));
        return payment;
    }

    private PaymentAttemptEntity paymentAttempt() {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setId(701L);
        attempt.setPaymentId(600L);
        attempt.setGatewayName("RAZORPAY");
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        return attempt;
    }
}
