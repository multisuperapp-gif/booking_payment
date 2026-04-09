package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingOtpPurpose;
import jakarta.validation.constraints.NotNull;

public record GenerateBookingOtpRequest(
        @NotNull(message = "booking id is required")
        Long bookingId,
        @NotNull(message = "otp purpose is required")
        BookingOtpPurpose purpose
) {
}
