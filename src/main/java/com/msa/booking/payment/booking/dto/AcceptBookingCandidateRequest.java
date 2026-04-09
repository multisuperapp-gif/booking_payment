package com.msa.booking.payment.booking.dto;

import jakarta.validation.constraints.NotNull;

public record AcceptBookingCandidateRequest(
        @NotNull(message = "request id is required")
        Long requestId,
        @NotNull(message = "candidate id is required")
        Long candidateId
) {
}
