package com.msa.booking.payment.integration.shoporders.dto;

import java.math.BigDecimal;
import java.util.List;

public final class ShopOrdersRuntimeSyncDtos {
    private ShopOrdersRuntimeSyncDtos() {
    }

    public record CreateOrderItemRequest(
            Long variantId,
            Integer quantity
    ) {
    }

    public record CreateOrderRequest(
            Long userId,
            Long addressId,
            String fulfillmentType,
            List<CreateOrderItemRequest> items
    ) {
    }

    public record CreatedOrderItemData(
            Long productId,
            Long variantId,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
    }

    public record OrderItemData(
            Long productId,
            Long variantId,
            Integer quantity
    ) {
    }

    public record CreatedOrderData(
            Long orderId,
            String orderCode,
            Long shopId,
            Long userId,
            String orderStatus,
            String paymentStatus,
            BigDecimal subtotalAmount,
            BigDecimal deliveryFeeAmount,
            BigDecimal totalAmount,
            BigDecimal platformFeeAmount,
            String currencyCode,
            List<CreatedOrderItemData> items
    ) {
    }

    public record RuntimeSyncRequest(
            String movementType,
            String movementReason
    ) {
    }

    public record OrderStateUpdateRequest(
            String paymentStatus,
            String orderStatus,
            Long changedByUserId,
            String reason,
            String refundPolicyApplied
    ) {
    }

    public record OrderFinanceContextData(
            Long orderId,
            String orderCode,
            Long shopId,
            Long userId,
            String orderStatus,
            String paymentStatus,
            BigDecimal subtotalAmount,
            BigDecimal deliveryFeeAmount,
            BigDecimal totalAmount,
            BigDecimal platformFeeAmount,
            String currencyCode
    ) {
    }
}
