package com.msa.booking.payment.booking.dto;

import com.msa.booking.payment.domain.enums.BookingRequestCandidateStatus;
import com.msa.booking.payment.domain.enums.BookingRequestStatus;

public record BookingCandidateDecisionData(
        Long requestId,
        Long candidateId,
        BookingRequestStatus requestStatus,
        BookingRequestCandidateStatus candidateStatus,
        int pendingCandidateCount
) {
}
