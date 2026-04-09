package com.msa.booking.payment.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UserCancelBookingRequest(
        @NotNull(message = "booking id is required")
        Long bookingId,
        @NotBlank(message = "reason is required")
        String reason
) {
}
