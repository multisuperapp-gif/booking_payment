package com.msa.booking.payment.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ShopOrderItemRequest(
        @NotNull(message = "variant id is required")
        Long variantId,
        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        Integer quantity
) {
}
