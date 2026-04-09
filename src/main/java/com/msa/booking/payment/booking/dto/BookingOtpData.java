package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingOtpPurpose;

import java.time.LocalDateTime;

public record BookingOtpData(
        Long bookingId,
        BookingOtpPurpose purpose,
        String otpCode,
        LocalDateTime expiresAt
) {
}
