package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProviderBookingHistoryData(
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
        LocalDateTime createdAt,
        String categoryLabel,
        String labourPricingModel
) {
}
