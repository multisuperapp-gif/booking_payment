package com.msa.booking.payment.order.dto;

import jakarta.validation.constraints.NotNull;

public record CancelShopOrderRequest(
        @NotNull(message = "order id is required")
        Long orderId,
        @NotNull(message = "user id is required")
        Long userId,
        String reason
) {
}
