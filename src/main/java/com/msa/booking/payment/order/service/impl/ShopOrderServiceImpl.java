package com.msa.booking.payment.order.service.impl;

import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.domain.enums.RefundLifecycleStatus;
import com.msa.booking.payment.integration.shoporders.ShopOrdersRuntimeSyncClient;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.CreatedOrderData;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderItemData;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.modules.settlement.service.SettlementLifecycleService;
import com.msa.booking.payment.order.dto.*;
import com.msa.booking.payment.order.service.ShopOrderFinanceContextService;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderFinanceContextData;
import com.msa.booking.payment.order.service.ShopOrderService;
import com.msa.booking.payment.order.service.ShopOrdersRuntimeSyncService;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentTransactionEntity;
import com.msa.booking.payment.persistence.entity.RefundEntity;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import com.msa.booking.payment.persistence.repository.RefundRepository;
import com.msa.booking.payment.persistence.repository.ShopOrderSupportRepository;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentTransactionStatus;
import com.msa.booking.payment.domain.enums.PaymentTransactionType;
import com.msa.booking.payment.storage.BillingDocumentLink;
import com.msa.booking.payment.storage.BillingDocumentStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ShopOrderServiceImpl implements ShopOrderService {
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;
    private final ShopOrderSupportRepository shopOrderSupportRepository;
    private final NotificationService notificationService;
    private final RazorpayGatewayService razorpayGatewayService;
    private final SettlementLifecycleService settlementLifecycleService;
    private final ShopOrderFinanceContextService shopOrderFinanceContextService;
    private final ShopOrdersRuntimeSyncService shopOrdersRuntimeSyncService;
    private final ShopOrdersRuntimeSyncClient shopOrdersRuntimeSyncClient;
    private final BillingDocumentStorageService billingDocumentStorageService;

    public ShopOrderServiceImpl(
            PaymentRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            RefundRepository refundRepository,
            ShopOrderSupportRepository shopOrderSupportRepository,
            NotificationService notificationService,
            RazorpayGatewayService razorpayGatewayService,
            SettlementLifecycleService settlementLifecycleService,
            ShopOrderFinanceContextService shopOrderFinanceContextService,
            ShopOrdersRuntimeSyncService shopOrdersRuntimeSyncService,
            ShopOrdersRuntimeSyncClient shopOrdersRuntimeSyncClient,
            BillingDocumentStorageService billingDocumentStorageService
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.refundRepository = refundRepository;
        this.shopOrderSupportRepository = shopOrderSupportRepository;
        this.notificationService = notificationService;
        this.razorpayGatewayService = razorpayGatewayService;
        this.settlementLifecycleService = settlementLifecycleService;
        this.shopOrderFinanceContextService = shopOrderFinanceContextService;
        this.shopOrdersRuntimeSyncService = shopOrdersRuntimeSyncService;
        this.shopOrdersRuntimeSyncClient = shopOrdersRuntimeSyncClient;
        this.billingDocumentStorageService = billingDocumentStorageService;
    }

    @Override
    @Transactional
    public ShopOrderData createOrder(CreateShopOrderRequest request) {
        validateOrderItems(request.items());
        CreatedOrderData createdOrder = requireCreatedOrder(
                shopOrdersRuntimeSyncClient.createOrder(
                        new ShopOrdersRuntimeSyncDtos.CreateOrderRequest(
                                request.userId(),
                                request.addressId(),
                                request.fulfillmentType() == null ? null : request.fulfillmentType().name(),
                                request.items().stream()
                                        .map(item -> new ShopOrdersRuntimeSyncDtos.CreateOrderItemRequest(item.variantId(), item.quantity()))
                                        .toList()
                        )
                )
        );

        notificationService.notifyUser(
                request.userId(),
                "SHOP_ORDER_PAYMENT_PENDING",
                "Complete your shop order payment",
                "Your order is ready. Complete payment to confirm it.",
                Map.of("orderId", createdOrder.orderId(), "orderCode", createdOrder.orderCode())
        );

        return toData(createdOrder, "Order created and inventory reserved.");
    }

    @Override
    @Transactional
    public ShopOrderData initiatePayment(InitiateShopOrderPaymentRequest request) {
        OrderFinanceContextData order = shopOrderFinanceContextService.loadRequired(request.orderId());
        if (!OrderLifecycleStatus.PAYMENT_PENDING.name().equalsIgnoreCase(order.orderStatus())) {
            throw new BadRequestException("Payment can be initiated only when order is waiting for payment.");
        }

        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.SHOP_ORDER, order.orderId())
                .orElseGet(() -> createPayment(order));
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS) {
            throw new BadRequestException("Payment is already completed for this order.");
        }
        PaymentAttemptEntity latestAttempt = paymentAttemptRepository
                .findTopByPaymentIdAndGatewayNameOrderByIdDesc(payment.getId(), "RAZORPAY")
                .orElse(null);
        if (isReusablePendingAttempt(latestAttempt)) {
            payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
            paymentRepository.save(payment);
            shopOrderFinanceContextService.updateStateRequired(
                    order.orderId(),
                    PayablePaymentStatus.PENDING.name(),
                    null,
                    order.userId(),
                    null,
                    null
            );
            return toData(order, OrderLifecycleStatus.valueOf(order.orderStatus()), PayablePaymentStatus.PENDING, payment.getPaymentCode(), razorpayGatewayService.configuredKeyId(), latestAttempt.getGatewayOrderId(), "Payment already initiated for shop order.");
        }
        RazorpayGatewayService.RazorpayOrderData gatewayOrder = razorpayGatewayService.createOrder(
                payment.getPaymentCode(),
                payment.getAmount(),
                payment.getCurrencyCode()
        );
        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        paymentRepository.save(payment);

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(payment.getId());
        attempt.setGatewayName("RAZORPAY");
        attempt.setGatewayOrderId(gatewayOrder.orderId());
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        attempt.setRequestedAmount(payment.getAmount());
        attempt.setResponseCode(gatewayOrder.status());
        attempt.setAttemptedAt(LocalDateTime.now());
        paymentAttemptRepository.save(attempt);

        shopOrderFinanceContextService.updateStateRequired(
                order.orderId(),
                PayablePaymentStatus.PENDING.name(),
                null,
                order.userId(),
                null,
                null
        );
        notificationService.notifyUser(
                order.userId(),
                "SHOP_ORDER_PAYMENT_PENDING",
                "Complete your shop order payment",
                "Your shop order is reserved. Complete payment to confirm it.",
                Map.of(
                        "orderId", order.orderId(),
                        "orderCode", order.orderCode(),
                        "paymentCode", payment.getPaymentCode(),
                        "razorpayOrderId", gatewayOrder.orderId()
                )
        );
        return toData(order, OrderLifecycleStatus.valueOf(order.orderStatus()), PayablePaymentStatus.PENDING, payment.getPaymentCode(), gatewayOrder.keyId(), gatewayOrder.orderId(), "Payment initiated for shop order.");
    }

    @Override
    @Transactional
    public ShopOrderData markPaymentSuccess(CompleteShopOrderPaymentRequest request) {
        OrderFinanceContextData order = shopOrderFinanceContextService.loadRequired(request.orderId());
        PaymentEntity payment = loadPaymentForOrder(request.paymentCode(), order.orderId());
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED) {
            return toData(order, OrderLifecycleStatus.valueOf(order.orderStatus()), parsePaymentStatus(order.paymentStatus()), payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment was already finalized.");
        }
        if (OrderLifecycleStatus.CANCELLED.name().equalsIgnoreCase(order.orderStatus())
                || payment.getPaymentStatus() == PaymentLifecycleStatus.FAILED) {
            return toData(order, OrderLifecycleStatus.CANCELLED, parsePaymentStatus(order.paymentStatus()), payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order is already cancelled, so late payment success was ignored.");
        }
        PaymentAttemptEntity attempt = paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc(request.razorpayOrderId())
                .orElseThrow(() -> new BadRequestException("Razorpay payment attempt not found."));
        if (!attempt.getPaymentId().equals(payment.getId())) {
            throw new BadRequestException("Razorpay order does not belong to this payment.");
        }
        if (request.razorpayPaymentId() == null || request.razorpayPaymentId().isBlank()) {
            throw new BadRequestException("Razorpay payment id is required.");
        }
        if (request.razorpaySignature() == null || request.razorpaySignature().isBlank()) {
            throw new BadRequestException("Razorpay signature is required.");
        }
        if (!razorpayGatewayService.verifyPaymentSignature(
                request.razorpayOrderId(),
                request.razorpayPaymentId(),
                request.razorpaySignature()
        )) {
            throw new BadRequestException("Razorpay payment signature verification failed.");
        }
        if (paymentTransactionRepository.findByGatewayTransactionId(request.razorpayPaymentId()).isPresent()) {
            return toData(order, OrderLifecycleStatus.valueOf(order.orderStatus()), parsePaymentStatus(order.paymentStatus()), payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment already verified.");
        }

        payment.setPaymentStatus(PaymentLifecycleStatus.SUCCESS);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        attempt.setAttemptStatus(PaymentAttemptStatus.SUCCESS);
        attempt.setResponseCode("captured");
        paymentAttemptRepository.save(attempt);

        PaymentTransactionEntity transaction = new PaymentTransactionEntity();
        transaction.setPaymentId(payment.getId());
        transaction.setGatewayTransactionId(request.razorpayPaymentId());
        transaction.setTransactionType(PaymentTransactionType.PAYMENT);
        transaction.setTransactionStatus(PaymentTransactionStatus.SUCCESS);
        transaction.setAmount(payment.getAmount());
        transaction.setProcessedAt(LocalDateTime.now());
        paymentTransactionRepository.save(transaction);

        consumeReservedInventory(order.orderId());
        shopOrderFinanceContextService.updateStateRequired(
                order.orderId(),
                PayablePaymentStatus.PAID.name(),
                OrderLifecycleStatus.PAYMENT_COMPLETED.name(),
                order.userId(),
                "Order payment completed",
                null
        );
        billingDocumentStorageService.storePaymentInvoice(
                payment,
                null,
                null,
                order.orderCode(),
                "Shop order payment invoice."
        );

        notificationService.notifyUser(
                order.userId(),
                "SHOP_ORDER_PAYMENT_SUCCESS",
                "Order payment successful",
                "Your shop order payment was completed successfully.",
                Map.of("orderId", order.orderId(), "orderCode", order.orderCode())
        );
        notifyShopOwner(
                order.shopId(),
                "SHOP_ORDER_RECEIVED",
                "New paid order received",
                "A new paid order is ready for acceptance.",
                Map.of("orderId", order.orderId(), "orderCode", order.orderCode())
        );
        shopOrdersRuntimeSyncService.recordOrderMovementAfterCommit(
                order.orderId(),
                "CONSUME",
                "Order payment completed and reserved stock consumed."
        );
        return toData(order, OrderLifecycleStatus.PAYMENT_COMPLETED, PayablePaymentStatus.PAID, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment completed successfully.");
    }

    @Override
    @Transactional
    public ShopOrderData markPaymentFailure(CompleteShopOrderPaymentRequest request) {
        OrderFinanceContextData order = shopOrderFinanceContextService.loadRequired(request.orderId());
        PaymentEntity payment = loadPaymentForOrder(request.paymentCode(), order.orderId());
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.FAILED
                && OrderLifecycleStatus.CANCELLED.name().equalsIgnoreCase(order.orderStatus())) {
            return toData(order, OrderLifecycleStatus.CANCELLED, PayablePaymentStatus.FAILED, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment failure was already recorded.");
        }
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED
                || PayablePaymentStatus.PAID.name().equalsIgnoreCase(order.paymentStatus())
                || PayablePaymentStatus.REFUNDED.name().equalsIgnoreCase(order.paymentStatus())) {
            return toData(order, OrderLifecycleStatus.valueOf(order.orderStatus()), parsePaymentStatus(order.paymentStatus()), payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment is already finalized, so late payment failure was ignored.");
        }
        PaymentAttemptEntity attempt = paymentAttemptRepository.findFirstByGatewayOrderIdOrderByAttemptedAtDesc(request.razorpayOrderId())
                .orElseThrow(() -> new BadRequestException("Razorpay payment attempt not found."));
        if (!attempt.getPaymentId().equals(payment.getId())) {
            throw new BadRequestException("Razorpay order does not belong to this payment.");
        }

        payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        attempt.setAttemptStatus(PaymentAttemptStatus.FAILED);
        attempt.setResponseCode(
                request.failureCode() != null && !request.failureCode().isBlank()
                        ? request.failureCode()
                        : "failed"
        );
        paymentAttemptRepository.save(attempt);

        releaseReservedInventory(order.orderId());
        shopOrderFinanceContextService.updateStateRequired(
                order.orderId(),
                PayablePaymentStatus.FAILED.name(),
                OrderLifecycleStatus.CANCELLED.name(),
                order.userId(),
                "Order payment failed",
                null
        );

        notificationService.notifyUser(
                order.userId(),
                "SHOP_ORDER_PAYMENT_FAILED",
                "Order payment failed",
                "Your shop order payment failed and the order was cancelled.",
                Map.of("orderId", order.orderId(), "orderCode", order.orderCode())
        );
        shopOrdersRuntimeSyncService.recordOrderMovementAfterCommit(
                order.orderId(),
                "RELEASE",
                "Order payment failed and reserved stock was released."
        );
        return toData(order, OrderLifecycleStatus.CANCELLED, PayablePaymentStatus.FAILED, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment failed and inventory was released.");
    }

    @Override
    @Transactional
    public ShopOrderData updateStatus(UpdateShopOrderStatusRequest request) {
        OrderFinanceContextData order = shopOrderFinanceContextService.loadRequired(request.orderId());
        if (request.newStatus() == OrderLifecycleStatus.PAYMENT_PENDING
                || request.newStatus() == OrderLifecycleStatus.PAYMENT_COMPLETED
                || request.newStatus() == OrderLifecycleStatus.CREATED) {
            throw new BadRequestException("Manual update is not allowed for this order status.");
        }

        OrderLifecycleStatus currentStatus = OrderLifecycleStatus.valueOf(order.orderStatus());
        validateStatusTransition(currentStatus, request.newStatus());

        if (request.newStatus() == OrderLifecycleStatus.CANCELLED) {
            if (currentStatus == OrderLifecycleStatus.OUT_FOR_DELIVERY
                    || currentStatus == OrderLifecycleStatus.DELIVERED) {
                throw new BadRequestException("Order cannot be cancelled after it is out for delivery.");
            }
            handleOrderCancellation(order, request.reason(), request.refundPolicyApplied(), request.changedByUserId());
            return toData(
                    order,
                    OrderLifecycleStatus.CANCELLED,
                    resolveCancelledPaymentStatus(order.paymentStatus()),
                    loadPaymentCode(order.orderId()),
                    null,
                    null,
                    "Shop order cancelled successfully."
            );
        }

        shopOrderFinanceContextService.updateStateRequired(
                order.orderId(),
                null,
                request.newStatus().name(),
                request.changedByUserId(),
                defaultReason(request.reason(), request.newStatus()),
                null
        );
        notificationService.notifyUser(
                order.userId(),
                "SHOP_ORDER_STATUS_UPDATED",
                "Order status updated",
                "Your shop order is now " + humanizeStatus(request.newStatus()) + ".",
                Map.of("orderId", order.orderId(), "orderCode", order.orderCode(), "status", request.newStatus().name())
        );
        return toData(
                order,
                request.newStatus(),
                parsePaymentStatus(order.paymentStatus()),
                loadPaymentCode(order.orderId()),
                null,
                null,
                "Shop order status updated."
        );
    }

    @Override
    @Transactional
    public ShopOrderData cancelByUser(CancelShopOrderRequest request) {
        OrderFinanceContextData order = shopOrderFinanceContextService.loadRequired(request.orderId());
        if (!order.userId().equals(request.userId())) {
            throw new BadRequestException("User is not allowed to cancel this order.");
        }
        OrderLifecycleStatus currentStatus = OrderLifecycleStatus.valueOf(order.orderStatus());
        if (currentStatus == OrderLifecycleStatus.OUT_FOR_DELIVERY
                || currentStatus == OrderLifecycleStatus.DELIVERED) {
            throw new BadRequestException("Order cannot be cancelled after it is out for delivery.");
        }
        if (currentStatus == OrderLifecycleStatus.CANCELLED) {
            return toData(
                    order,
                    OrderLifecycleStatus.CANCELLED,
                    parsePaymentStatus(order.paymentStatus()),
                    loadPaymentCode(order.orderId()),
                    null,
                    null,
                    "Shop order was already cancelled."
            );
        }

        handleOrderCancellation(order, request.reason(), null, request.userId());
        notifyShopOwner(
                order.shopId(),
                "SHOP_ORDER_CANCELLED",
                "Order cancelled by user",
                "A user cancelled a shop order before delivery.",
                Map.of("orderId", order.orderId(), "orderCode", order.orderCode())
        );
        return toData(
                order,
                OrderLifecycleStatus.CANCELLED,
                resolveCancelledPaymentStatus(order.paymentStatus()),
                loadPaymentCode(order.orderId()),
                null,
                null,
                "Shop order cancelled by user."
        );
    }

    private void handleOrderCancellation(OrderFinanceContextData order, String reason, String refundPolicyApplied, Long changedByUserId) {
        String nextPaymentStatus = order.paymentStatus();
        if (PayablePaymentStatus.PAID.name().equalsIgnoreCase(order.paymentStatus())) {
            restockConsumedInventory(order);
            applyFullRefund(order, defaultReason(reason, OrderLifecycleStatus.CANCELLED));
            shopOrdersRuntimeSyncService.recordOrderMovementAfterCommit(
                    order.orderId(),
                    "RESTOCK",
                    "Order cancelled and consumed stock was restocked."
            );
        } else {
            releaseReservedInventory(order.orderId());
            if (PayablePaymentStatus.PENDING.name().equalsIgnoreCase(order.paymentStatus())) {
                nextPaymentStatus = PayablePaymentStatus.FAILED.name();
            }
            shopOrdersRuntimeSyncService.recordOrderMovementAfterCommit(
                    order.orderId(),
                    "RELEASE",
                    "Order cancelled before payment completion and reserved stock was released."
            );
        }

        shopOrderFinanceContextService.updateStateRequired(
                order.orderId(),
                nextPaymentStatus,
                OrderLifecycleStatus.CANCELLED.name(),
                changedByUserId,
                defaultReason(reason, OrderLifecycleStatus.CANCELLED),
                refundPolicyApplied
        );
        notificationService.notifyUser(
                order.userId(),
                "SHOP_ORDER_CANCELLED",
                "Order cancelled",
                "Your shop order was cancelled.",
                Map.of("orderId", order.orderId(), "orderCode", order.orderCode())
        );
    }

    private void validateOrderItems(List<ShopOrderItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new BadRequestException("At least one shop order item is required.");
        }
    }

    private void validateStatusTransition(OrderLifecycleStatus currentStatus, OrderLifecycleStatus newStatus) {
        switch (currentStatus) {
            case PAYMENT_COMPLETED -> {
                if (newStatus != OrderLifecycleStatus.ACCEPTED && newStatus != OrderLifecycleStatus.CANCELLED) {
                    throw new BadRequestException("Only ACCEPTED or CANCELLED is allowed after payment completion.");
                }
            }
            case ACCEPTED -> {
                if (newStatus != OrderLifecycleStatus.PREPARING && newStatus != OrderLifecycleStatus.CANCELLED) {
                    throw new BadRequestException("Only PREPARING or CANCELLED is allowed after ACCEPTED.");
                }
            }
            case PREPARING -> {
                if (newStatus != OrderLifecycleStatus.DISPATCHED
                        && newStatus != OrderLifecycleStatus.OUT_FOR_DELIVERY
                        && newStatus != OrderLifecycleStatus.CANCELLED) {
                    throw new BadRequestException("Only DISPATCHED, OUT_FOR_DELIVERY, or CANCELLED is allowed after PREPARING.");
                }
            }
            case DISPATCHED -> {
                if (newStatus != OrderLifecycleStatus.OUT_FOR_DELIVERY && newStatus != OrderLifecycleStatus.CANCELLED) {
                    throw new BadRequestException("Only OUT_FOR_DELIVERY or CANCELLED is allowed after DISPATCHED.");
                }
            }
            case OUT_FOR_DELIVERY -> {
                if (newStatus != OrderLifecycleStatus.DELIVERED) {
                    throw new BadRequestException("Only DELIVERED is allowed after OUT_FOR_DELIVERY.");
                }
            }
            default -> throw new BadRequestException("Manual update is not allowed from the current order state.");
        }
    }

    private void consumeReservedInventory(Long orderId) {
        for (OrderItemData orderItem : shopOrderFinanceContextService.loadItemsRequired(orderId)) {
            int updated = shopOrderSupportRepository.consumeReservedInventory(orderItem.variantId(), orderItem.quantity());
            if (updated == 0) {
                throw new BadRequestException("Reserved inventory could not be committed for variant " + orderItem.variantId() + ".");
            }
        }
    }

    private void releaseReservedInventory(Long orderId) {
        for (OrderItemData orderItem : shopOrderFinanceContextService.loadItemsRequired(orderId)) {
            shopOrderSupportRepository.releaseReservedInventory(orderItem.variantId(), orderItem.quantity());
        }
    }

    private void restockConsumedInventory(OrderFinanceContextData order) {
        for (OrderItemData orderItem : shopOrderFinanceContextService.loadItemsRequired(order.orderId())) {
            shopOrderSupportRepository.restockInventory(orderItem.variantId(), orderItem.quantity());
        }
    }

    private void applyFullRefund(OrderFinanceContextData order, String reason) {
        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.SHOP_ORDER, order.orderId())
                .orElseThrow(() -> new BadRequestException("Order payment not found."));
        Optional<RefundEntity> existingRefund = refundRepository.findTopByPaymentIdOrderByIdDesc(payment.getId());

        payment.setPaymentStatus(PaymentLifecycleStatus.REFUNDED);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        if (existingRefund.isPresent()) {
            RefundEntity refund = existingRefund.get();
            if (refund.getRefundStatus() == RefundLifecycleStatus.SUCCESS) {
                if (refund.getApprovedAmount() == null) {
                    refund.setApprovedAmount(payment.getAmount());
                }
                if (refund.getCompletedAt() == null) {
                    refund.setCompletedAt(LocalDateTime.now());
                }
                if (refund.getReason() == null || refund.getReason().isBlank()) {
                    refund.setReason(reason);
                }
                refundRepository.save(refund);
                settlementLifecycleService.recordSuccessfulRefund(payment, refund.getApprovedAmount());
                billingDocumentStorageService.storeRefundCreditNote(payment, refund, order.orderCode(), "Shop order refund credit note.");
                notifyOrderRefundSuccess(order, refund);
                return;
            }
        }

        RefundEntity refund = new RefundEntity();
        refund.setPaymentId(payment.getId());
        refund.setRefundCode("RFN-" + order.orderCode());
        refund.setRefundStatus(RefundLifecycleStatus.SUCCESS);
        refund.setRequestedAmount(payment.getAmount());
        refund.setApprovedAmount(payment.getAmount());
        refund.setReason(reason);
        refund.setInitiatedAt(LocalDateTime.now());
        refund.setCompletedAt(LocalDateTime.now());
        refundRepository.save(refund);
        settlementLifecycleService.recordSuccessfulRefund(payment, refund.getApprovedAmount());
        billingDocumentStorageService.storeRefundCreditNote(payment, refund, order.orderCode(), "Shop order refund credit note.");
        notifyOrderRefundSuccess(order, refund);
    }

    private PaymentEntity createPayment(OrderFinanceContextData order) {
        PaymentEntity payment = new PaymentEntity();
        payment.setPaymentCode("PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        payment.setPayableType(PayableType.SHOP_ORDER);
        payment.setPayableId(order.orderId());
        payment.setPayerUserId(order.userId());
        payment.setPaymentStatus(PaymentLifecycleStatus.INITIATED);
        payment.setAmount(order.totalAmount());
        payment.setCurrencyCode(order.currencyCode());
        payment.setInitiatedAt(LocalDateTime.now());
        return paymentRepository.save(payment);
    }

    private PaymentEntity loadPaymentForOrder(String paymentCode, Long orderId) {
        PaymentEntity payment = paymentRepository.findByPaymentCode(paymentCode)
                .orElseThrow(() -> new BadRequestException("Payment not found."));
        if (payment.getPayableType() != PayableType.SHOP_ORDER || !payment.getPayableId().equals(orderId)) {
            throw new BadRequestException("Payment does not belong to this order.");
        }
        return payment;
    }

    private void notifyShopOwner(Long shopId, String type, String title, String body, Map<String, Object> payload) {
        Map<String, Object> ownerPayload = new java.util.LinkedHashMap<>(payload);
        ownerPayload.put("appContext", "PROVIDER_APP");
        shopOrderSupportRepository.findShopOwnerUserId(shopId)
                .ifPresent(ownerUserId -> notificationService.notifyUser(ownerUserId, type, title, body, ownerPayload));
    }

    private void notifyOrderRefundSuccess(OrderFinanceContextData order, RefundEntity refund) {
        notificationService.notifyUser(
                order.userId(),
                "SHOP_ORDER_REFUND_SUCCESS",
                "Refund completed",
                "Your refund has been completed for the cancelled shop order.",
                Map.of(
                        "orderId", order.orderId(),
                        "orderCode", order.orderCode(),
                        "refundCode", refund.getRefundCode(),
                        "refundStatus", refund.getRefundStatus().name()
                )
        );
    }

    private String loadPaymentCode(Long orderId) {
        return paymentRepository.findByPayableTypeAndPayableId(PayableType.SHOP_ORDER, orderId)
                .map(PaymentEntity::getPaymentCode)
                .orElse(null);
    }

    private boolean isReusablePendingAttempt(PaymentAttemptEntity attempt) {
        return attempt != null
                && attempt.getGatewayOrderId() != null
                && !attempt.getGatewayOrderId().isBlank()
                && (attempt.getAttemptStatus() == PaymentAttemptStatus.PENDING
                || attempt.getAttemptStatus() == PaymentAttemptStatus.INITIATED);
    }

    private String defaultReason(String reason, OrderLifecycleStatus status) {
        if (reason != null && !reason.isBlank()) {
            return reason.trim();
        }
        return "Order moved to " + status.name();
    }

    private String humanizeStatus(OrderLifecycleStatus status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private PayablePaymentStatus parsePaymentStatus(String paymentStatus) {
        return paymentStatus == null ? PayablePaymentStatus.UNPAID : PayablePaymentStatus.valueOf(paymentStatus);
    }

    private PayablePaymentStatus resolveCancelledPaymentStatus(String existingPaymentStatus) {
        if (PayablePaymentStatus.PAID.name().equalsIgnoreCase(existingPaymentStatus)) {
            return PayablePaymentStatus.REFUNDED;
        }
        if (PayablePaymentStatus.PENDING.name().equalsIgnoreCase(existingPaymentStatus)) {
            return PayablePaymentStatus.FAILED;
        }
        return parsePaymentStatus(existingPaymentStatus);
    }

    private ShopOrderData toData(
            OrderFinanceContextData order,
            OrderLifecycleStatus orderStatus,
            PayablePaymentStatus paymentStatus,
            String paymentCode,
            String razorpayKeyId,
            String razorpayOrderId,
            String note
    ) {
        BillingDocumentLinks documentLinks = resolveDocumentLinks(order.orderId());
        return new ShopOrderData(
                order.orderId(),
                order.orderCode(),
                order.shopId(),
                orderStatus,
                paymentStatus,
                paymentCode,
                "RAZORPAY",
                razorpayKeyId,
                razorpayOrderId,
                order.subtotalAmount() == null ? BigDecimal.ZERO : order.subtotalAmount(),
                order.deliveryFeeAmount() == null ? BigDecimal.ZERO : order.deliveryFeeAmount(),
                order.platformFeeAmount() == null ? BigDecimal.ZERO : order.platformFeeAmount(),
                order.totalAmount() == null ? BigDecimal.ZERO : order.totalAmount(),
                order.currencyCode(),
                amountInPaise(order.totalAmount() == null ? BigDecimal.ZERO : order.totalAmount()),
                note,
                documentLinks.invoiceObjectKey(),
                documentLinks.invoiceAccessUrl(),
                documentLinks.refundObjectKey(),
                documentLinks.refundAccessUrl()
        );
    }

    private ShopOrderData toData(CreatedOrderData order, String note) {
        return new ShopOrderData(
                order.orderId(),
                order.orderCode(),
                order.shopId(),
                OrderLifecycleStatus.valueOf(order.orderStatus()),
                parsePaymentStatus(order.paymentStatus()),
                null,
                "RAZORPAY",
                null,
                null,
                order.subtotalAmount(),
                order.deliveryFeeAmount(),
                order.platformFeeAmount(),
                order.totalAmount(),
                order.currencyCode(),
                amountInPaise(order.totalAmount()),
                note,
                null,
                null,
                null,
                null
        );
    }

    private BillingDocumentLinks resolveDocumentLinks(Long orderId) {
        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.SHOP_ORDER, orderId).orElse(null);
        BillingDocumentLink invoiceLink = payment == null
                ? new BillingDocumentLink(null, null)
                : billingDocumentStorageService.resolvePaymentInvoiceLink(payment, null, null);
        RefundEntity refund = payment == null ? null : refundRepository.findTopByPaymentIdOrderByIdDesc(payment.getId()).orElse(null);
        BillingDocumentLink refundLink = refund == null
                ? new BillingDocumentLink(null, null)
                : billingDocumentStorageService.resolveRefundCreditNoteLink(refund);
        return new BillingDocumentLinks(
                invoiceLink.objectKey(),
                invoiceLink.accessUrl(),
                refundLink.objectKey(),
                refundLink.accessUrl()
        );
    }

    private record BillingDocumentLinks(
            String invoiceObjectKey,
            String invoiceAccessUrl,
            String refundObjectKey,
            String refundAccessUrl
    ) {
    }

    private CreatedOrderData requireCreatedOrder(
            com.msa.booking.payment.common.api.ApiResponse<CreatedOrderData> response
    ) {
        if (response == null || !response.success() || response.data() == null) {
            throw new BadRequestException(response == null ? "Shop order could not be created." : response.message());
        }
        return response.data();
    }

    private long amountInPaise(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).longValue();
    }
}
