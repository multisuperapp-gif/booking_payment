package com.msa.booking.payment.booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitBookingReviewRequest(
        @NotNull(message = "booking id is required")
        Long bookingId,
        @NotNull(message = "rating is required")
        @Min(value = 1, message = "rating must be between 1 and 5")
        @Max(value = 5, message = "rating must be between 1 and 5")
        Integer rating,
        @Size(max = 500, message = "comment can be at most 500 characters")
        String comment
) {
}
