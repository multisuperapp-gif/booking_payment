package com.msa.booking.payment.payment.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.*;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.modules.payment.service.PaymentWebhookEventService;
import com.msa.booking.payment.order.service.ShopOrderFinanceContextService;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.msa.booking.payment.payment.service.RazorpayWebhookService;
import com.msa.booking.payment.persistence.entity.*;
import com.msa.booking.payment.persistence.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class RazorpayWebhookServiceImpl implements RazorpayWebhookService {
    private final RazorpayGatewayService razorpayGatewayService;
    private final ObjectMapper objectMapper;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final BookingRepository bookingRepository;
    private final BookingSupportRepository bookingSupportRepository;
    private final ShopOrderSupportRepository shopOrderSupportRepository;
    private final BookingHistoryService bookingHistoryService;
    private final NotificationService notificationService;
    private final PaymentWebhookEventService paymentWebhookEventService;
    private final ShopOrderFinanceContextService shopOrderFinanceContextService;

    public RazorpayWebhookServiceImpl(
            RazorpayGatewayService razorpayGatewayService,
            ObjectMapper objectMapper,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentRepository paymentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            BookingRepository bookingRepository,
            BookingSupportRepository bookingSupportRepository,
            ShopOrderSupportRepository shopOrderSupportRepository,
            BookingHistoryService bookingHistoryService,
            NotificationService notificationService,
            PaymentWebhookEventService paymentWebhookEventService,
            ShopOrderFinanceContextService shopOrderFinanceContextService
    ) {
        this.razorpayGatewayService = razorpayGatewayService;
        this.objectMapper = objectMapper;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentRepository = paymentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.bookingRepository = bookingRepository;
        this.bookingSupportRepository = bookingSupportRepository;
        this.shopOrderSupportRepository = shopOrderSupportRepository;
        this.bookingHistoryService = bookingHistoryService;
        this.notificationService = notificationService;
        this.paymentWebhookEventService = paymentWebhookEventService;
        this.shopOrderFinanceContextService = shopOrderFinanceContextService;
    }

    @Override
    @Transactional
    public void processWebhook(String requestBody, String razorpaySignature, String razorpayEventId) {
        if (!razorpayGatewayService.verifyWebhookSignature(requestBody, razorpaySignature)) {
            throw new BadRequestException("Invalid Razorpay webhook signature.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(requestBody);
        } catch (Exception exception) {
            throw new BadRequestException("Invalid Razorpay webhook payload.");
        }

        String event = root.path("event").asText("");
        JsonNode paymentNode = root.at("/payload/payment/entity");
        if (paymentNode.isMissingNode()) {
            return;
        }
        String webhookKey = paymentWebhookEventService.resolveWebhookKey(razorpayEventId, requestBody);

        String gatewayOrderId = paymentNode.path("order_id").asText("");
        String gatewayPaymentId = paymentNode.path("id").asText("");
        if (gatewayOrderId.isBlank()) {
            return;
        }

        PaymentAttemptEntity attempt = paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc(gatewayOrderId)
                .orElse(null);
        if (attempt == null) {
            return;
        }
        PaymentEntity payment = paymentRepository.findById(attempt.getPaymentId())
                .orElseThrow(() -> new BadRequestException("Payment not found for Razorpay webhook."));
        if (!paymentWebhookEventService.registerIfFirst(
                "LEGACY_PAYMENT",
                webhookKey,
                event,
                gatewayOrderId,
                gatewayPaymentId,
                payment.getId()
        )) {
            return;
        }

        switch (event) {
            case "payment.captured", "order.paid" -> {
                markSuccess(payment, attempt, gatewayOrderId, gatewayPaymentId);
                paymentWebhookEventService.markProcessed("LEGACY_PAYMENT", webhookKey, true, "Payment captured");
            }
            case "payment.failed" -> {
                markFailure(payment, attempt, gatewayOrderId, paymentNode.path("error_code").asText("failed"));
                paymentWebhookEventService.markProcessed("LEGACY_PAYMENT", webhookKey, true, "Payment marked failed");
            }
            default -> paymentWebhookEventService.markProcessed("LEGACY_PAYMENT", webhookKey, false, "Ignored: unsupported event");
        }
    }

    private void markSuccess(PaymentEntity payment, PaymentAttemptEntity attempt, String gatewayOrderId, String gatewayPaymentId) {
        if (isLateSuccessIgnored(payment)) {
            return;
        }
        if (paymentTransactionRepository.findByGatewayTransactionId(gatewayPaymentId).isPresent()) {
            return;
        }

        payment.setPaymentStatus(PaymentLifecycleStatus.SUCCESS);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        attempt.setAttemptStatus(PaymentAttemptStatus.SUCCESS);
        attempt.setResponseCode("captured");
        paymentAttemptRepository.save(attempt);

        PaymentTransactionEntity transaction = new PaymentTransactionEntity();
        transaction.setPaymentId(payment.getId());
        transaction.setGatewayTransactionId(gatewayPaymentId);
        transaction.setTransactionType(PaymentTransactionType.PAYMENT);
        transaction.setTransactionStatus(PaymentTransactionStatus.SUCCESS);
        transaction.setAmount(payment.getAmount());
        transaction.setProcessedAt(LocalDateTime.now());
        paymentTransactionRepository.save(transaction);

        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new BadRequestException("Booking not found for payment webhook."));
            if (booking.getBookingStatus() != BookingLifecycleStatus.PAYMENT_COMPLETED) {
                String oldStatus = booking.getBookingStatus().name();
                booking.setPaymentStatus(PayablePaymentStatus.PAID);
                booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_COMPLETED);
                bookingRepository.save(booking);
                bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), booking.getUserId(), "Payment completed from Razorpay webhook");
                notificationService.notifyUser(
                        booking.getUserId(),
                        "BOOKING_PAYMENT_SUCCESS",
                        "Payment successful",
                        "Your booking payment was completed successfully.",
                        Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode(), "razorpayOrderId", gatewayOrderId)
                );
                notifyProvider(
                        booking,
                        "BOOKING_PAYMENT_SUCCESS_PROVIDER",
                        "Customer payment received",
                        "Customer payment is complete. You can proceed with the booking.",
                        Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode(), "razorpayOrderId", gatewayOrderId)
                );
            }
            return;
        }

        var order = shopOrderFinanceContextService.loadRequired(payment.getPayableId());
        if (!OrderLifecycleStatus.PAYMENT_COMPLETED.name().equalsIgnoreCase(order.orderStatus())) {
            consumeReservedInventory(order.orderId());
            shopOrderFinanceContextService.updateStateRequired(
                    order.orderId(),
                    PayablePaymentStatus.PAID.name(),
                    OrderLifecycleStatus.PAYMENT_COMPLETED.name(),
                    order.userId(),
                    "Order payment completed from Razorpay webhook",
                    null
            );
            notificationService.notifyUser(
                    order.userId(),
                    "SHOP_ORDER_PAYMENT_SUCCESS",
                    "Order payment successful",
                    "Your shop order payment was completed successfully.",
                    Map.of("orderId", order.orderId(), "orderCode", order.orderCode(), "razorpayOrderId", gatewayOrderId)
            );
            shopOrderSupportRepository.findShopOwnerUserId(order.shopId())
                    .ifPresent(ownerUserId -> notificationService.notifyUser(
                            ownerUserId,
                            "SHOP_ORDER_RECEIVED",
                            "New paid order received",
                            "A new paid order is ready for acceptance.",
                            Map.of("orderId", order.orderId(), "orderCode", order.orderCode())
                    ));
        }
    }

    private void markFailure(PaymentEntity payment, PaymentAttemptEntity attempt, String gatewayOrderId, String failureCode) {
        if (isLateFailureIgnored(payment)) {
            return;
        }
        payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        attempt.setAttemptStatus(PaymentAttemptStatus.FAILED);
        attempt.setResponseCode(failureCode);
        paymentAttemptRepository.save(attempt);

        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new BadRequestException("Booking not found for payment webhook."));
            if (booking.getBookingStatus() != BookingLifecycleStatus.CANCELLED) {
                String oldStatus = booking.getBookingStatus().name();
                booking.setPaymentStatus(PayablePaymentStatus.FAILED);
                booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
                bookingRepository.save(booking);
                bookingHistoryService.recordBookingStatus(booking, oldStatus, booking.getBookingStatus().name(), booking.getUserId(), "Payment failed from Razorpay webhook");
                if (booking.getProviderEntityType() == ProviderEntityType.SERVICE_PROVIDER) {
                    bookingSupportRepository.incrementAvailableServiceMen(booking.getProviderEntityId());
                }
                notificationService.notifyUser(
                        booking.getUserId(),
                        "BOOKING_PAYMENT_FAILED",
                        "Payment failed",
                        "Your booking payment failed and the booking was cancelled.",
                        Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode(), "razorpayOrderId", gatewayOrderId)
                );
                notifyProvider(
                        booking,
                        "BOOKING_PAYMENT_FAILED_PROVIDER",
                        "Booking payment failed",
                        "Customer payment failed, so this booking was cancelled.",
                        Map.of("bookingId", booking.getId(), "bookingCode", booking.getBookingCode(), "razorpayOrderId", gatewayOrderId)
                );
            }
            return;
        }

        var order = shopOrderFinanceContextService.loadRequired(payment.getPayableId());
        if (!OrderLifecycleStatus.CANCELLED.name().equalsIgnoreCase(order.orderStatus())) {
            shopOrderFinanceContextService.updateStateRequired(
                    order.orderId(),
                    PayablePaymentStatus.FAILED.name(),
                    OrderLifecycleStatus.CANCELLED.name(),
                    order.userId(),
                    "Order payment failed from Razorpay webhook",
                    null
            );
            releaseReservedInventory(order.orderId());
            notificationService.notifyUser(
                    order.userId(),
                    "SHOP_ORDER_PAYMENT_FAILED",
                    "Order payment failed",
                    "Your shop order payment failed and the order was cancelled.",
                    Map.of("orderId", order.orderId(), "orderCode", order.orderCode(), "razorpayOrderId", gatewayOrderId)
            );
        }
    }

    private void notifyProvider(BookingEntity booking, String type, String title, String body, Map<String, Object> payload) {
        Long providerUserId = booking.getProviderEntityType() == ProviderEntityType.LABOUR
                ? bookingSupportRepository.findLabourUserId(booking.getProviderEntityId()).orElse(null)
                : bookingSupportRepository.findServiceProviderUserId(booking.getProviderEntityId()).orElse(null);
        if (providerUserId != null) {
            Map<String, Object> providerPayload = new java.util.LinkedHashMap<>(payload);
            providerPayload.put("appContext", "PROVIDER_APP");
            notificationService.notifyUser(providerUserId, type, title, body, providerPayload);
        }
    }

    private void consumeReservedInventory(Long orderId) {
        for (var item : shopOrderFinanceContextService.loadItemsRequired(orderId)) {
            int updated = shopOrderSupportRepository.consumeReservedInventory(item.variantId(), item.quantity());
            if (updated == 0) {
                throw new BadRequestException("Reserved inventory could not be committed for variant " + item.variantId() + ".");
            }
        }
    }

    private void releaseReservedInventory(Long orderId) {
        for (var item : shopOrderFinanceContextService.loadItemsRequired(orderId)) {
            shopOrderSupportRepository.releaseReservedInventory(item.variantId(), item.quantity());
        }
    }

    private boolean isLateSuccessIgnored(PaymentEntity payment) {
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.FAILED
                || payment.getPaymentStatus() == PaymentLifecycleStatus.CANCELLED
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED) {
            return true;
        }
        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new BadRequestException("Booking not found for payment webhook."));
            return booking.getBookingStatus() == BookingLifecycleStatus.CANCELLED
                    || booking.getPaymentStatus() == PayablePaymentStatus.FAILED
                    || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED;
        }
        var order = shopOrderFinanceContextService.loadRequired(payment.getPayableId());
        return OrderLifecycleStatus.CANCELLED.name().equalsIgnoreCase(order.orderStatus())
                || PayablePaymentStatus.FAILED.name().equalsIgnoreCase(order.paymentStatus())
                || PayablePaymentStatus.REFUNDED.name().equalsIgnoreCase(order.paymentStatus());
    }

    private boolean isLateFailureIgnored(PaymentEntity payment) {
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED) {
            return true;
        }
        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new BadRequestException("Booking not found for payment webhook."));
            return booking.getPaymentStatus() == PayablePaymentStatus.PAID
                    || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED;
        }
        var order = shopOrderFinanceContextService.loadRequired(payment.getPayableId());
        return PayablePaymentStatus.PAID.name().equalsIgnoreCase(order.paymentStatus())
                || PayablePaymentStatus.REFUNDED.name().equalsIgnoreCase(order.paymentStatus());
    }
}
