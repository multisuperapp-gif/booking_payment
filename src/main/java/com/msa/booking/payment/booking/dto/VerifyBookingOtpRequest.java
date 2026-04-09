package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingOtpPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VerifyBookingOtpRequest(
        @NotNull(message = "booking id is required")
        Long bookingId,
        @NotNull(message = "otp purpose is required")
        BookingOtpPurpose purpose,
        @NotBlank(message = "otp code is required")
        String otpCode
) {
}
