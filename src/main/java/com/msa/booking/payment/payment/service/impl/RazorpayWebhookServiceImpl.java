package com.msa.booking.payment.payment.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.*;
import com.msa.booking.payment.notification.service.NotificationService;
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
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final BookingSupportRepository bookingSupportRepository;
    private final ShopOrderSupportRepository shopOrderSupportRepository;
    private final BookingHistoryService bookingHistoryService;
    private final NotificationService notificationService;

    public RazorpayWebhookServiceImpl(
            RazorpayGatewayService razorpayGatewayService,
            ObjectMapper objectMapper,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentRepository paymentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            BookingRepository bookingRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            BookingSupportRepository bookingSupportRepository,
            ShopOrderSupportRepository shopOrderSupportRepository,
            BookingHistoryService bookingHistoryService,
            NotificationService notificationService
    ) {
        this.razorpayGatewayService = razorpayGatewayService;
        this.objectMapper = objectMapper;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentRepository = paymentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.bookingRepository = bookingRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.bookingSupportRepository = bookingSupportRepository;
        this.shopOrderSupportRepository = shopOrderSupportRepository;
        this.bookingHistoryService = bookingHistoryService;
        this.notificationService = notificationService;
    }

    @Override
    @Transactional
    public void processWebhook(String requestBody, String razorpaySignature) {
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

        switch (event) {
            case "payment.captured", "order.paid" -> markSuccess(payment, attempt, gatewayOrderId, gatewayPaymentId);
            case "payment.failed" -> markFailure(payment, attempt, gatewayOrderId, paymentNode.path("error_code").asText("failed"));
            default -> {
            }
        }
    }

    private void markSuccess(PaymentEntity payment, PaymentAttemptEntity attempt, String gatewayOrderId, String gatewayPaymentId) {
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
            }
            return;
        }

        OrderEntity order = orderRepository.findById(payment.getPayableId())
                .orElseThrow(() -> new BadRequestException("Order not found for payment webhook."));
        if (order.getOrderStatus() != OrderLifecycleStatus.PAYMENT_COMPLETED) {
            consumeReservedInventory(order);
            String oldStatus = order.getOrderStatus().name();
            order.setPaymentStatus(PayablePaymentStatus.PAID);
            order.setOrderStatus(OrderLifecycleStatus.PAYMENT_COMPLETED);
            orderRepository.save(order);
            bookingHistoryService.recordOrderStatus(order, oldStatus, order.getOrderStatus().name(), order.getUserId(), "Order payment completed from Razorpay webhook");
            notificationService.notifyUser(
                    order.getUserId(),
                    "SHOP_ORDER_PAYMENT_SUCCESS",
                    "Order payment successful",
                    "Your shop order payment was completed successfully.",
                    Map.of("orderId", order.getId(), "orderCode", order.getOrderCode(), "razorpayOrderId", gatewayOrderId)
            );
            shopOrderSupportRepository.findShopOwnerUserId(order.getShopId())
                    .ifPresent(ownerUserId -> notificationService.notifyUser(
                            ownerUserId,
                            "SHOP_ORDER_RECEIVED",
                            "New paid order received",
                            "A new paid order is ready for acceptance.",
                            Map.of("orderId", order.getId(), "orderCode", order.getOrderCode())
                    ));
        }
    }

    private void markFailure(PaymentEntity payment, PaymentAttemptEntity attempt, String gatewayOrderId, String failureCode) {
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
            }
            return;
        }

        OrderEntity order = orderRepository.findById(payment.getPayableId())
                .orElseThrow(() -> new BadRequestException("Order not found for payment webhook."));
        if (order.getOrderStatus() != OrderLifecycleStatus.CANCELLED) {
            String oldStatus = order.getOrderStatus().name();
            order.setPaymentStatus(PayablePaymentStatus.FAILED);
            order.setOrderStatus(OrderLifecycleStatus.CANCELLED);
            orderRepository.save(order);
            releaseReservedInventory(order);
            bookingHistoryService.recordOrderStatus(order, oldStatus, order.getOrderStatus().name(), order.getUserId(), "Order payment failed from Razorpay webhook");
            notificationService.notifyUser(
                    order.getUserId(),
                    "SHOP_ORDER_PAYMENT_FAILED",
                    "Order payment failed",
                    "Your shop order payment failed and the order was cancelled.",
                    Map.of("orderId", order.getId(), "orderCode", order.getOrderCode(), "razorpayOrderId", gatewayOrderId)
            );
        }
    }

    private void consumeReservedInventory(OrderEntity order) {
        for (OrderItemEntity item : orderItemRepository.findByOrderId(order.getId())) {
            int updated = shopOrderSupportRepository.consumeReservedInventory(item.getVariantId(), item.getQuantity());
            if (updated == 0) {
                throw new BadRequestException("Reserved inventory could not be committed for variant " + item.getVariantId() + ".");
            }
        }
    }

    private void releaseReservedInventory(OrderEntity order) {
        for (OrderItemEntity item : orderItemRepository.findByOrderId(order.getId())) {
            shopOrderSupportRepository.releaseReservedInventory(item.getVariantId(), item.getQuantity());
        }
    }
}
