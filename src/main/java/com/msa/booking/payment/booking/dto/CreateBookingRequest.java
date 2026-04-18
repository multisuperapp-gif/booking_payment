package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingRequestMode;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record CreateBookingRequest(
        @NotNull(message = "booking type is required")
        BookingFlowType bookingType,
        @NotNull(message = "request mode is required")
        BookingRequestMode requestMode,
        @NotNull(message = "user id is required")
        Long userId,
        @NotNull(message = "address id is required")
        Long addressId,
        @NotNull(message = "scheduled start is required")
        LocalDateTime scheduledStartAt,
        ProviderEntityType targetProviderEntityType,
        Long targetProviderEntityId,
        Long categoryId,
        Long subcategoryId,
        String labourPricingModel,
        BigDecimal priceMinAmount,
        BigDecimal priceMaxAmount,
        BigDecimal searchLatitude,
        BigDecimal searchLongitude,
        Integer requestedProviderCount,
        @Valid
        List<BookingRequestCandidateInput> candidates
) {
}
