package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProviderActiveBookingData(
        Long bookingId,
        String bookingCode,
        BookingFlowType bookingType,
        ProviderEntityType providerEntityType,
        Long providerEntityId,
        BookingLifecycleStatus bookingStatus,
        PayablePaymentStatus paymentStatus,
        String customerName,
        String customerPhone,
        BigDecimal quotedPriceAmount,
        BigDecimal distanceKm,
        LocalDateTime scheduledStartAt,
        LocalDateTime paymentDueAt,
        LocalDateTime reachByAt,
        String categoryLabel,
        String labourPricingModel,
        String addressLabel,
        String addressLine1,
        String addressLine2,
        String landmark,
        String city,
        String state,
        String postalCode,
        BigDecimal destinationLatitude,
        BigDecimal destinationLongitude,
        String startOtpCode,
        LocalDateTime startOtpExpiresAt,
        String completeOtpCode,
        LocalDateTime completeOtpExpiresAt,
        String mutualCancelOtpCode,
        LocalDateTime mutualCancelOtpExpiresAt
) {
}
