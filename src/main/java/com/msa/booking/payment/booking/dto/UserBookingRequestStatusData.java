package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.BookingRequestStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record UserBookingRequestStatusData(
        Long requestId,
        String requestCode,
        BookingFlowType bookingType,
        BookingRequestStatus requestStatus,
        Long candidateId,
        ProviderEntityType providerEntityType,
        Long providerEntityId,
        String providerName,
        String providerPhone,
        BigDecimal quotedPriceAmount,
        BigDecimal totalAcceptedQuotedPriceAmount,
        BigDecimal totalAcceptedBookingChargeAmount,
        BigDecimal distanceKm,
        String providerPhotoObjectKey,
        BigDecimal providerLatitude,
        BigDecimal providerLongitude,
        LocalDateTime paymentDueAt,
        LocalDateTime reachByAt,
        String labourPricingModel,
        Integer requestedProviderCount,
        Integer acceptedProviderCount,
        Integer pendingProviderCount,
        Long bookingId,
        String bookingCode,
        BookingLifecycleStatus bookingStatus,
        PayablePaymentStatus paymentStatus
) {
}
