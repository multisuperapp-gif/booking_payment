package com.msa.booking.payment.order.service.impl;

import com.msa.booking.payment.booking.support.BookingHistoryService;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.OrderFulfillmentType;
import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.domain.enums.RefundLifecycleStatus;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.modules.settlement.service.SettlementLifecycleService;
import com.msa.booking.payment.order.dto.*;
import com.msa.booking.payment.order.projection.ShopCheckoutItemProjection;
import com.msa.booking.payment.order.projection.ShopDeliveryRuleProjection;
import com.msa.booking.payment.order.service.ShopOrderService;
import com.msa.booking.payment.persistence.entity.OrderEntity;
import com.msa.booking.payment.persistence.entity.OrderItemEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentTransactionEntity;
import com.msa.booking.payment.persistence.entity.RefundEntity;
import com.msa.booking.payment.persistence.repository.OrderItemRepository;
import com.msa.booking.payment.persistence.repository.OrderRepository;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import com.msa.booking.payment.persistence.repository.RefundRepository;
import com.msa.booking.payment.persistence.repository.ShopOrderSupportRepository;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentTransactionStatus;
import com.msa.booking.payment.domain.enums.PaymentTransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ShopOrderServiceImpl implements ShopOrderService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;
    private final ShopOrderSupportRepository shopOrderSupportRepository;
    private final BookingPolicyService bookingPolicyService;
    private final BookingHistoryService bookingHistoryService;
    private final NotificationService notificationService;
    private final RazorpayGatewayService razorpayGatewayService;
    private final SettlementLifecycleService settlementLifecycleService;

    public ShopOrderServiceImpl(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PaymentRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            RefundRepository refundRepository,
            ShopOrderSupportRepository shopOrderSupportRepository,
            BookingPolicyService bookingPolicyService,
            BookingHistoryService bookingHistoryService,
            NotificationService notificationService,
            RazorpayGatewayService razorpayGatewayService,
            SettlementLifecycleService settlementLifecycleService
    ) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.refundRepository = refundRepository;
        this.shopOrderSupportRepository = shopOrderSupportRepository;
        this.bookingPolicyService = bookingPolicyService;
        this.bookingHistoryService = bookingHistoryService;
        this.notificationService = notificationService;
        this.razorpayGatewayService = razorpayGatewayService;
        this.settlementLifecycleService = settlementLifecycleService;
    }

    @Override
    @Transactional
    public ShopOrderData createOrder(CreateShopOrderRequest request) {
        validateOrderItems(request.items());
        Map<Long, Integer> quantitiesByVariant = mergeQuantities(request.items());
        List<ShopCheckoutItemProjection> checkoutItems = loadCheckoutItems(quantitiesByVariant.keySet());

        Long shopId = resolveSingleShop(checkoutItems);
        ShopDeliveryRuleProjection deliveryRule = shopOrderSupportRepository.findPrimaryDeliveryRuleByShopId(shopId)
                .orElseThrow(() -> new BadRequestException("Primary shop delivery configuration not found."));

        BigDecimal subtotal = BigDecimal.ZERO;
        for (ShopCheckoutItemProjection item : checkoutItems) {
            Integer requestedQuantity = quantitiesByVariant.get(item.getVariantId());
            validateCheckoutItem(item, requestedQuantity);
            subtotal = subtotal.add(item.getSellingPrice().multiply(BigDecimal.valueOf(requestedQuantity)));
        }

        BigDecimal deliveryFee = resolveDeliveryFee(request.fulfillmentType(), deliveryRule, subtotal);
        BigDecimal platformFee = bookingPolicyService.shopPlatformFee();
        BigDecimal totalAmount = subtotal.add(deliveryFee).add(platformFee);

        OrderEntity order = new OrderEntity();
        order.setOrderCode(generateOrderCode());
        order.setUserId(request.userId());
        order.setShopId(shopId);
        order.setShopLocationId(deliveryRule.getShopLocationId());
        order.setAddressId(request.addressId());
        order.setOrderStatus(OrderLifecycleStatus.PAYMENT_PENDING);
        order.setPaymentStatus(PayablePaymentStatus.UNPAID);
        order.setFulfillmentType(request.fulfillmentType());
        order.setSubtotalAmount(subtotal);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setDeliveryFeeAmount(deliveryFee);
        order.setPlatformFeeAmount(platformFee);
        order.setPackagingFeeAmount(BigDecimal.ZERO);
        order.setTipAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalAmount(totalAmount);
        order.setCurrencyCode("INR");
        OrderEntity savedOrder = orderRepository.save(order);

        List<OrderItemEntity> orderItems = new ArrayList<>();
        for (ShopCheckoutItemProjection item : checkoutItems) {
            Integer requestedQuantity = quantitiesByVariant.get(item.getVariantId());
            int reserved = shopOrderSupportRepository.reserveInventory(item.getVariantId(), requestedQuantity);
            if (reserved == 0) {
                throw new BadRequestException("Inventory is no longer available for variant " + item.getVariantId() + ".");
            }

            OrderItemEntity orderItem = new OrderItemEntity();
            orderItem.setOrderId(savedOrder.getId());
            orderItem.setProductId(item.getProductId());
            orderItem.setVariantId(item.getVariantId());
            orderItem.setQuantity(requestedQuantity);
            orderItem.setUnitPriceSnapshot(item.getSellingPrice());
            orderItem.setTaxSnapshot(BigDecimal.ZERO);
            orderItem.setLineTotal(item.getSellingPrice().multiply(BigDecimal.valueOf(requestedQuantity)));
            orderItems.add(orderItem);
        }
        orderItemRepository.saveAll(orderItems);

        bookingHistoryService.recordOrderStatus(savedOrder, null, savedOrder.getOrderStatus().name(), request.userId(), "Shop order created");
        notificationService.notifyUser(
                request.userId(),
                "SHOP_ORDER_PAYMENT_PENDING",
                "Complete your shop order payment",
                "Your order is ready. Complete payment to confirm it.",
                Map.of("orderId", savedOrder.getId(), "orderCode", savedOrder.getOrderCode())
        );

        return toData(savedOrder, null, null, null, "Order created and inventory reserved.");
    }

    @Override
    @Transactional
    public ShopOrderData initiatePayment(InitiateShopOrderPaymentRequest request) {
        OrderEntity order = loadOrder(request.orderId());
        if (order.getOrderStatus() != OrderLifecycleStatus.PAYMENT_PENDING) {
            throw new BadRequestException("Payment can be initiated only when order is waiting for payment.");
        }

        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.ORDER, order.getId())
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
            order.setPaymentStatus(PayablePaymentStatus.PENDING);
            orderRepository.save(order);
            return toData(order, payment.getPaymentCode(), razorpayGatewayService.configuredKeyId(), latestAttempt.getGatewayOrderId(), "Payment already initiated for shop order.");
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

        order.setPaymentStatus(PayablePaymentStatus.PENDING);
        orderRepository.save(order);
        notificationService.notifyUser(
                order.getUserId(),
                "SHOP_ORDER_PAYMENT_PENDING",
                "Complete your shop order payment",
                "Your shop order is reserved. Complete payment to confirm it.",
                Map.of(
                        "orderId", order.getId(),
                        "orderCode", order.getOrderCode(),
                        "paymentCode", payment.getPaymentCode(),
                        "razorpayOrderId", gatewayOrder.orderId()
                )
        );
        return toData(order, payment.getPaymentCode(), gatewayOrder.keyId(), gatewayOrder.orderId(), "Payment initiated for shop order.");
    }

    @Override
    @Transactional
    public ShopOrderData markPaymentSuccess(CompleteShopOrderPaymentRequest request) {
        OrderEntity order = loadOrder(request.orderId());
        PaymentEntity payment = loadPaymentForOrder(request.paymentCode(), order.getId());
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED) {
            return toData(order, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment was already finalized.");
        }
        if (order.getOrderStatus() == OrderLifecycleStatus.CANCELLED
                || payment.getPaymentStatus() == PaymentLifecycleStatus.FAILED) {
            return toData(order, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order is already cancelled, so late payment success was ignored.");
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
            return toData(order, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment already verified.");
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

        consumeReservedInventory(order);

        String oldStatus = order.getOrderStatus().name();
        order.setPaymentStatus(PayablePaymentStatus.PAID);
        order.setOrderStatus(OrderLifecycleStatus.PAYMENT_COMPLETED);
        orderRepository.save(order);
        bookingHistoryService.recordOrderStatus(order, oldStatus, order.getOrderStatus().name(), order.getUserId(), "Order payment completed");

        notificationService.notifyUser(
                order.getUserId(),
                "SHOP_ORDER_PAYMENT_SUCCESS",
                "Order payment successful",
                "Your shop order payment was completed successfully.",
                Map.of("orderId", order.getId(), "orderCode", order.getOrderCode())
        );
        notifyShopOwner(
                order.getShopId(),
                "SHOP_ORDER_RECEIVED",
                "New paid order received",
                "A new paid order is ready for acceptance.",
                Map.of("orderId", order.getId(), "orderCode", order.getOrderCode())
        );
        return toData(order, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment completed successfully.");
    }

    @Override
    @Transactional
    public ShopOrderData markPaymentFailure(CompleteShopOrderPaymentRequest request) {
        OrderEntity order = loadOrder(request.orderId());
        PaymentEntity payment = loadPaymentForOrder(request.paymentCode(), order.getId());
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.FAILED
                && order.getOrderStatus() == OrderLifecycleStatus.CANCELLED) {
            return toData(order, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment failure was already recorded.");
        }
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED
                || order.getPaymentStatus() == PayablePaymentStatus.PAID
                || order.getPaymentStatus() == PayablePaymentStatus.REFUNDED) {
            return toData(order, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment is already finalized, so late payment failure was ignored.");
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

        releaseReservedInventory(order);

        String oldStatus = order.getOrderStatus().name();
        order.setPaymentStatus(PayablePaymentStatus.FAILED);
        order.setOrderStatus(OrderLifecycleStatus.CANCELLED);
        orderRepository.save(order);
        bookingHistoryService.recordOrderStatus(order, oldStatus, order.getOrderStatus().name(), order.getUserId(), "Order payment failed");

        notificationService.notifyUser(
                order.getUserId(),
                "SHOP_ORDER_PAYMENT_FAILED",
                "Order payment failed",
                "Your shop order payment failed and the order was cancelled.",
                Map.of("orderId", order.getId(), "orderCode", order.getOrderCode())
        );
        return toData(order, payment.getPaymentCode(), null, request.razorpayOrderId(), "Shop order payment failed and inventory was released.");
    }

    @Override
    @Transactional
    public ShopOrderData updateStatus(UpdateShopOrderStatusRequest request) {
        OrderEntity order = loadOrder(request.orderId());
        if (request.newStatus() == OrderLifecycleStatus.PAYMENT_PENDING
                || request.newStatus() == OrderLifecycleStatus.PAYMENT_COMPLETED
                || request.newStatus() == OrderLifecycleStatus.CREATED) {
            throw new BadRequestException("Manual update is not allowed for this order status.");
        }

        String oldStatus = order.getOrderStatus().name();
        validateStatusTransition(order.getOrderStatus(), request.newStatus());

        if (request.newStatus() == OrderLifecycleStatus.CANCELLED) {
            if (order.getOrderStatus() == OrderLifecycleStatus.OUT_FOR_DELIVERY
                    || order.getOrderStatus() == OrderLifecycleStatus.DELIVERED) {
                throw new BadRequestException("Order cannot be cancelled after it is out for delivery.");
            }
            handleOrderCancellation(order, request.reason(), request.changedByUserId());
            return toData(order, loadPaymentCode(order.getId()), null, null, "Shop order cancelled successfully.");
        }

        order.setOrderStatus(request.newStatus());
        orderRepository.save(order);
        bookingHistoryService.recordOrderStatus(order, oldStatus, order.getOrderStatus().name(), request.changedByUserId(), defaultReason(request.reason(), request.newStatus()));
        notificationService.notifyUser(
                order.getUserId(),
                "SHOP_ORDER_STATUS_UPDATED",
                "Order status updated",
                "Your shop order is now " + humanizeStatus(order.getOrderStatus()) + ".",
                Map.of("orderId", order.getId(), "orderCode", order.getOrderCode(), "status", order.getOrderStatus().name())
        );
        return toData(order, loadPaymentCode(order.getId()), null, null, "Shop order status updated.");
    }

    @Override
    @Transactional
    public ShopOrderData cancelByUser(CancelShopOrderRequest request) {
        OrderEntity order = loadOrder(request.orderId());
        if (!order.getUserId().equals(request.userId())) {
            throw new BadRequestException("User is not allowed to cancel this order.");
        }
        if (order.getOrderStatus() == OrderLifecycleStatus.OUT_FOR_DELIVERY
                || order.getOrderStatus() == OrderLifecycleStatus.DELIVERED) {
            throw new BadRequestException("Order cannot be cancelled after it is out for delivery.");
        }
        if (order.getOrderStatus() == OrderLifecycleStatus.CANCELLED) {
            return toData(order, loadPaymentCode(order.getId()), null, null, "Shop order was already cancelled.");
        }

        handleOrderCancellation(order, request.reason(), request.userId());
        notifyShopOwner(
                order.getShopId(),
                "SHOP_ORDER_CANCELLED",
                "Order cancelled by user",
                "A user cancelled a shop order before delivery.",
                Map.of("orderId", order.getId(), "orderCode", order.getOrderCode())
        );
        return toData(order, loadPaymentCode(order.getId()), null, null, "Shop order cancelled by user.");
    }

    private void handleOrderCancellation(OrderEntity order, String reason, Long changedByUserId) {
        String oldStatus = order.getOrderStatus().name();
        order.setOrderStatus(OrderLifecycleStatus.CANCELLED);

        if (order.getPaymentStatus() == PayablePaymentStatus.PAID) {
            restockConsumedInventory(order);
            applyFullRefund(order, defaultReason(reason, OrderLifecycleStatus.CANCELLED));
        } else {
            releaseReservedInventory(order);
            if (order.getPaymentStatus() == PayablePaymentStatus.PENDING) {
                order.setPaymentStatus(PayablePaymentStatus.FAILED);
            }
        }

        orderRepository.save(order);
        bookingHistoryService.recordOrderStatus(order, oldStatus, order.getOrderStatus().name(), changedByUserId, defaultReason(reason, OrderLifecycleStatus.CANCELLED));
        notificationService.notifyUser(
                order.getUserId(),
                "SHOP_ORDER_CANCELLED",
                "Order cancelled",
                "Your shop order was cancelled.",
                Map.of("orderId", order.getId(), "orderCode", order.getOrderCode())
        );
    }

    private void validateOrderItems(List<ShopOrderItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new BadRequestException("At least one shop order item is required.");
        }
    }

    private Map<Long, Integer> mergeQuantities(List<ShopOrderItemRequest> items) {
        Map<Long, Integer> quantitiesByVariant = new LinkedHashMap<>();
        for (ShopOrderItemRequest item : items) {
            quantitiesByVariant.merge(item.variantId(), item.quantity(), Integer::sum);
        }
        return quantitiesByVariant;
    }

    private List<ShopCheckoutItemProjection> loadCheckoutItems(Collection<Long> variantIds) {
        List<ShopCheckoutItemProjection> checkoutItems = shopOrderSupportRepository.findCheckoutItemsByVariantIds(variantIds);
        if (checkoutItems.size() != variantIds.size()) {
            throw new BadRequestException("One or more selected shop items are no longer available.");
        }
        return checkoutItems;
    }

    private Long resolveSingleShop(List<ShopCheckoutItemProjection> checkoutItems) {
        Set<Long> shopIds = new HashSet<>();
        for (ShopCheckoutItemProjection item : checkoutItems) {
            shopIds.add(item.getShopId());
        }
        if (shopIds.size() != 1) {
            throw new BadRequestException("Items from multiple shops cannot be ordered together. Clear the cart before switching shops.");
        }
        return shopIds.iterator().next();
    }

    private void validateCheckoutItem(ShopCheckoutItemProjection item, Integer requestedQuantity) {
        if (!Boolean.TRUE.equals(item.getProductActive())) {
            throw new BadRequestException("Selected product is inactive.");
        }
        if (item.getShopLocationId() == null) {
            throw new BadRequestException("Primary approved shop location is not available.");
        }
        if (!"IN_STOCK".equalsIgnoreCase(item.getInventoryStatus()) && !"LOW_STOCK".equalsIgnoreCase(item.getInventoryStatus())) {
            throw new BadRequestException("Selected item is out of stock.");
        }
        int availableToReserve = safeInt(item.getQuantityAvailable()) - safeInt(item.getReservedQuantity());
        if (availableToReserve < requestedQuantity) {
            throw new BadRequestException("Requested quantity is not available for variant " + item.getVariantId() + ".");
        }
    }

    private BigDecimal resolveDeliveryFee(OrderFulfillmentType fulfillmentType, ShopDeliveryRuleProjection deliveryRule, BigDecimal subtotal) {
        if (fulfillmentType == OrderFulfillmentType.PICKUP) {
            return BigDecimal.ZERO;
        }
        if ("PICKUP_ONLY".equalsIgnoreCase(deliveryRule.getDeliveryType())) {
            throw new BadRequestException("This shop currently supports pickup only.");
        }
        BigDecimal minOrderAmount = zeroIfNull(deliveryRule.getMinOrderAmount());
        if (subtotal.compareTo(minOrderAmount) < 0) {
            throw new BadRequestException("Minimum order amount for delivery is " + minOrderAmount + ".");
        }
        BigDecimal freeDeliveryAbove = deliveryRule.getFreeDeliveryAbove();
        if (freeDeliveryAbove != null && subtotal.compareTo(freeDeliveryAbove) >= 0) {
            return BigDecimal.ZERO;
        }
        return zeroIfNull(deliveryRule.getDeliveryFee());
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

    private void consumeReservedInventory(OrderEntity order) {
        for (OrderItemEntity orderItem : orderItemRepository.findByOrderId(order.getId())) {
            int updated = shopOrderSupportRepository.consumeReservedInventory(orderItem.getVariantId(), orderItem.getQuantity());
            if (updated == 0) {
                throw new BadRequestException("Reserved inventory could not be committed for variant " + orderItem.getVariantId() + ".");
            }
        }
    }

    private void releaseReservedInventory(OrderEntity order) {
        for (OrderItemEntity orderItem : orderItemRepository.findByOrderId(order.getId())) {
            shopOrderSupportRepository.releaseReservedInventory(orderItem.getVariantId(), orderItem.getQuantity());
        }
    }

    private void restockConsumedInventory(OrderEntity order) {
        for (OrderItemEntity orderItem : orderItemRepository.findByOrderId(order.getId())) {
            shopOrderSupportRepository.restockInventory(orderItem.getVariantId(), orderItem.getQuantity());
        }
    }

    private void applyFullRefund(OrderEntity order, String reason) {
        PaymentEntity payment = paymentRepository.findByPayableTypeAndPayableId(PayableType.ORDER, order.getId())
                .orElseThrow(() -> new BadRequestException("Order payment not found."));
        Optional<RefundEntity> existingRefund = refundRepository.findTopByPaymentIdOrderByIdDesc(payment.getId());

        payment.setPaymentStatus(PaymentLifecycleStatus.REFUNDED);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        order.setPaymentStatus(PayablePaymentStatus.REFUNDED);

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
                notifyOrderRefundSuccess(order, refund);
                return;
            }
        }

        RefundEntity refund = new RefundEntity();
        refund.setPaymentId(payment.getId());
        refund.setRefundCode("RFN-" + order.getOrderCode());
        refund.setRefundStatus(RefundLifecycleStatus.SUCCESS);
        refund.setRequestedAmount(payment.getAmount());
        refund.setApprovedAmount(payment.getAmount());
        refund.setReason(reason);
        refund.setInitiatedAt(LocalDateTime.now());
        refund.setCompletedAt(LocalDateTime.now());
        refundRepository.save(refund);
        settlementLifecycleService.recordSuccessfulRefund(payment, refund.getApprovedAmount());
        notifyOrderRefundSuccess(order, refund);
    }

    private PaymentEntity createPayment(OrderEntity order) {
        PaymentEntity payment = new PaymentEntity();
        payment.setPaymentCode("PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        payment.setPayableType(PayableType.ORDER);
        payment.setPayableId(order.getId());
        payment.setPayerUserId(order.getUserId());
        payment.setPaymentStatus(PaymentLifecycleStatus.INITIATED);
        payment.setAmount(order.getTotalAmount());
        payment.setCurrencyCode(order.getCurrencyCode());
        payment.setInitiatedAt(LocalDateTime.now());
        return paymentRepository.save(payment);
    }

    private OrderEntity loadOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BadRequestException("Order not found."));
    }

    private PaymentEntity loadPaymentForOrder(String paymentCode, Long orderId) {
        PaymentEntity payment = paymentRepository.findByPaymentCode(paymentCode)
                .orElseThrow(() -> new BadRequestException("Payment not found."));
        if (payment.getPayableType() != PayableType.ORDER || !payment.getPayableId().equals(orderId)) {
            throw new BadRequestException("Payment does not belong to this order.");
        }
        return payment;
    }

    private void notifyShopOwner(Long shopId, String type, String title, String body, Map<String, Object> payload) {
        shopOrderSupportRepository.findShopOwnerUserId(shopId)
                .ifPresent(ownerUserId -> notificationService.notifyUser(ownerUserId, type, title, body, payload));
    }

    private void notifyOrderRefundSuccess(OrderEntity order, RefundEntity refund) {
        notificationService.notifyUser(
                order.getUserId(),
                "SHOP_ORDER_REFUND_SUCCESS",
                "Refund completed",
                "Your refund has been completed for the cancelled shop order.",
                Map.of(
                        "orderId", order.getId(),
                        "orderCode", order.getOrderCode(),
                        "refundCode", refund.getRefundCode(),
                        "refundStatus", refund.getRefundStatus().name()
                )
        );
    }

    private String loadPaymentCode(Long orderId) {
        return paymentRepository.findByPayableTypeAndPayableId(PayableType.ORDER, orderId)
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

    private String generateOrderCode() {
        return "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String humanizeStatus(OrderLifecycleStatus status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private ShopOrderData toData(OrderEntity order, String paymentCode, String razorpayKeyId, String razorpayOrderId, String note) {
        return new ShopOrderData(
                order.getId(),
                order.getOrderCode(),
                order.getShopId(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                paymentCode,
                "RAZORPAY",
                razorpayKeyId,
                razorpayOrderId,
                order.getSubtotalAmount(),
                order.getDeliveryFeeAmount(),
                order.getPlatformFeeAmount(),
                order.getTotalAmount(),
                order.getCurrencyCode(),
                amountInPaise(order.getTotalAmount()),
                note
        );
    }

    private long amountInPaise(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).longValue();
    }
}
