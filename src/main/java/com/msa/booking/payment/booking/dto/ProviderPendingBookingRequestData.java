package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.ProviderEntityType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProviderPendingBookingRequestData(
        Long requestId,
        String requestCode,
        BookingFlowType bookingType,
        ProviderEntityType providerEntityType,
        Long providerEntityId,
        Long candidateId,
        String customerName,
        BigDecimal quotedPriceAmount,
        BigDecimal distanceKm,
        LocalDateTime scheduledStartAt,
        LocalDateTime expiresAt
) {
}
