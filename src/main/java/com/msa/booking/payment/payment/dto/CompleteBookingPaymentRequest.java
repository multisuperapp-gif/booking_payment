package com.msa.booking.payment.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CompleteBookingPaymentRequest(
        @NotNull(message = "booking id is required")
        Long bookingId,
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
