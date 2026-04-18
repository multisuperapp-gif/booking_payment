package com.msa.booking.payment.payment.dto;

public record InitiateBookingPaymentRequest(
        Long bookingId,
        Long bookingRequestId
) {
}
