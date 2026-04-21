package com.msa.booking.payment.booking.dto;

import java.time.LocalDateTime;

public record BookingReviewData(
        Long bookingId,
        int rating,
        String comment,
        LocalDateTime reviewedAt
) {
}
