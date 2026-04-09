package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingRequestMode;
import com.msa.booking.payment.domain.enums.BookingRequestStatus;

import java.time.LocalDateTime;
import java.util.List;

public record BookingRequestData(
        Long id,
        String requestCode,
        BookingFlowType bookingType,
        BookingRequestMode requestMode,
        BookingRequestStatus requestStatus,
        Long userId,
        Long addressId,
        LocalDateTime scheduledStartAt,
        LocalDateTime expiresAt,
        Integer timeoutSeconds,
        List<BookingRequestCandidateData> candidates
) {
}
