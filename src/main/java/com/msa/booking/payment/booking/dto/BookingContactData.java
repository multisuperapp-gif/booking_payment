package com.msa.booking.payment.booking.dto;

public record BookingContactData(
        Long userId,
        String fullName,
        String phone
) {
}
