package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.ProviderEntityType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record BookingRequestCandidateInput(
        @NotNull(message = "candidate provider entity type is required")
        ProviderEntityType providerEntityType,
        @NotNull(message = "candidate provider entity id is required")
        Long providerEntityId,
        BigDecimal quotedPriceAmount,
        BigDecimal distanceKm
) {
}
