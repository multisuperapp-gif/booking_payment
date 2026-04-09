package com.msa.booking.payment.booking.dto;

public record BookingContactPairData(
        String bookingCode,
        BookingContactData user,
        BookingContactData provider
) {
}
