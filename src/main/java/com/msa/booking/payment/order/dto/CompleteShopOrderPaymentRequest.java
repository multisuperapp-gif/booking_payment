package com.msa.booking.payment.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CompleteShopOrderPaymentRequest(
        @NotNull(message = "order id is required")
        Long orderId,
        @NotBlank(message = "payment code is required")
        String paymentCode,
        @NotBlank(message = "razorpay order id is required")
        String razorpayOrderId,
        String razorpayPaymentId,
        String razorpaySignature,
        String failureCode,
        String failureMessage
) {
}
