package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingRequestCandidateStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingRequestCandidateData(
        Long id,
        ProviderEntityType providerEntityType,
        Long providerEntityId,
        BookingRequestCandidateStatus candidateStatus,
        BigDecimal quotedPriceAmount,
        BigDecimal distanceKm,
        LocalDateTime expiresAt
) {
}
