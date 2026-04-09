package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;

public record BookingLifecycleData(
        Long bookingId,
        String bookingCode,
        BookingLifecycleStatus bookingStatus,
        PayablePaymentStatus paymentStatus,
        String note
) {
}
