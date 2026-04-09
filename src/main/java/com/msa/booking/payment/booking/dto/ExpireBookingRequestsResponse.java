package com.msa.booking.payment.booking.dto;

public record ExpireBookingRequestsResponse(
        int expiredRequests,
        int expiredCandidates
) {
}
