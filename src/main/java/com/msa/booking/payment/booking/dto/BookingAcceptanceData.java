package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;

import java.math.BigDecimal;

public record BookingAcceptanceData(
        Long requestId,
        Long candidateId,
        Long bookingId,
        String bookingCode,
        BookingLifecycleStatus bookingStatus,
        PayablePaymentStatus paymentStatus,
        BigDecimal estimatedAmount,
        int closedCandidateCount
) {
}
