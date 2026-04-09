package com.msa.booking.payment.payment.dto;

import jakarta.validation.constraints.NotNull;

public record InitiateBookingPaymentRequest(
        @NotNull(message = "booking id is required")
        Long bookingId
) {
}
