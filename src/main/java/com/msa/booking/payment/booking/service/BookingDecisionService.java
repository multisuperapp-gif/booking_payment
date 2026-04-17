package com.msa.booking.payment.booking.service;

import com.msa.booking.payment.booking.dto.*;

public interface BookingDecisionService {
    BookingAcceptanceData acceptCandidate(Long actingUserId, AcceptBookingCandidateRequest request);

    BookingCandidateDecisionData rejectCandidate(Long actingUserId, RejectBookingCandidateRequest request);
}
