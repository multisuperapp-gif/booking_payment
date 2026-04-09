package com.msa.booking.payment.order.dto;

import jakarta.validation.constraints.NotNull;

public record InitiateShopOrderPaymentRequest(
        @NotNull(message = "order id is required")
        Long orderId
) {
}
