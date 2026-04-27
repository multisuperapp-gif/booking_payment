package com.msa.booking.payment.modules.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.config.RazorpayProperties;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentInitiateRequest;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentInitiateResponse;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.WebhookAcknowledgeResponse;
import com.msa.booking.payment.modules.settlement.service.SettlementLifecycleService;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.order.service.ShopOrderFinanceContextService;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.repository.BookingActionOtpRepository;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestCandidateRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestRepository;
import com.msa.booking.payment.persistence.repository.BookingStatusHistoryRepository;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import com.msa.booking.payment.persistence.repository.ShopOrderSupportRepository;
import com.msa.booking.payment.storage.BillingDocumentStorageService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    private ShopOrderSupportRepository shopOrderSupportRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingRequestRepository bookingRequestRepository;
    @Mock
    private BookingRequestCandidateRepository bookingRequestCandidateRepository;
    @Mock
    private BookingStatusHistoryRepository bookingStatusHistoryRepository;
    @Mock
    private BookingActionOtpRepository bookingActionOtpRepository;
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
    @Mock
    private ShopOrderFinanceContextService shopOrderFinanceContextService;
    @Mock
    private BillingDocumentStorageService billingDocumentStorageService;

    private PaymentLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new PaymentLifecycleService(
                paymentRepository,
                paymentAttemptRepository,
                paymentTransactionRepository,
                shopOrderSupportRepository,
                bookingRepository,
                bookingRequestRepository,
                bookingRequestCandidateRepository,
                bookingStatusHistoryRepository,
                bookingActionOtpRepository,
                razorpayProperties,
                razorpaySignatureService,
                paymentWebhookEventService,
                settlementLifecycleService,
                notificationService,
                bookingPolicyService,
                shopOrderFinanceContextService,
                new ObjectMapper(),
                billingDocumentStorageService
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

        PaymentEntity payment = payment();

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
    void initiateReopensFailedShopOrderAndReReservesInventory() {
        PaymentEntity payment = payment();
        payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);
        payment.setCompletedAt(LocalDateTime.now().minusMinutes(1));

        when(paymentRepository.findByPaymentCode("PAY-ORDER")).thenReturn(Optional.of(payment));
        when(shopOrderFinanceContextService.loadRequired(44L)).thenReturn(orderContext("CANCELLED", "FAILED"));
        when(shopOrderFinanceContextService.loadItemsRequired(44L)).thenReturn(List.of(
                new ShopOrdersRuntimeSyncDtos.OrderItemData(1001L, 501L, 2)
        ));
        when(shopOrderSupportRepository.reserveInventory(501L, 2)).thenReturn(1);
        when(razorpayProperties.isEnabled()).thenReturn(true);
        when(razorpayProperties.getKeyId()).thenReturn("rzp_test_key");
        when(razorpayProperties.getKeySecret()).thenReturn("rzp_test_secret");
        when(paymentAttemptRepository.findTopByPaymentIdAndGatewayNameOrderByIdDesc(700L, "RAZORPAY"))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentAttemptRepository.save(any(PaymentAttemptEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentInitiateResponse response = service.initiate(88L, "PAY-ORDER", new PaymentInitiateRequest("RAZORPAY"));

        assertEquals("PENDING", response.paymentStatus());
        verify(shopOrderSupportRepository).reserveInventory(501L, 2);
        verify(shopOrderFinanceContextService).updateStateRequired(
                44L,
                "PENDING",
                "PAYMENT_PENDING",
                88L,
                "Payment retry initiated",
                null
        );
    }

    @Test
    void initiateRetryFailsWhenInventoryCannotBeReservedAgain() {
        PaymentEntity payment = payment();
        payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);

        when(paymentRepository.findByPaymentCode("PAY-ORDER")).thenReturn(Optional.of(payment));
        when(shopOrderFinanceContextService.loadRequired(44L)).thenReturn(orderContext("CANCELLED", "FAILED"));
        when(shopOrderFinanceContextService.loadItemsRequired(44L)).thenReturn(List.of(
                new ShopOrdersRuntimeSyncDtos.OrderItemData(1001L, 501L, 2)
        ));
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

    private PaymentEntity payment() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(700L);
        payment.setPaymentCode("PAY-ORDER");
        payment.setPayableId(44L);
        payment.setPayableType(PayableType.SHOP_ORDER);
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        payment.setAmount(BigDecimal.valueOf(199));
        payment.setCurrencyCode("INR");
        payment.setInitiatedAt(LocalDateTime.now().minusMinutes(3));
        payment.setPayerUserId(88L);
        return payment;
    }

    private ShopOrdersRuntimeSyncDtos.OrderFinanceContextData orderContext(String orderStatus, String paymentStatus) {
        return new ShopOrdersRuntimeSyncDtos.OrderFinanceContextData(
                44L,
                "ORD-FAILED",
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
}
