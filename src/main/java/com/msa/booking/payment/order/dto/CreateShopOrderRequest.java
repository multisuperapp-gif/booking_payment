package com.msa.booking.payment.order.dto;

import com.msa.booking.payment.domain.enums.OrderFulfillmentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateShopOrderRequest(
        @NotNull(message = "user id is required")
        Long userId,
        @NotNull(message = "address id is required")
        Long addressId,
        @NotNull(message = "fulfillment type is required")
        OrderFulfillmentType fulfillmentType,
        @Valid
        @NotEmpty(message = "at least one order item is required")
        List<ShopOrderItemRequest> items
) {
}
