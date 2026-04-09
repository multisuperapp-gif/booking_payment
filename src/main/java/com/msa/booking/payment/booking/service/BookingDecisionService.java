package com.msa.booking.payment.booking.service;

import com.msa.booking.payment.booking.dto.*;

public interface BookingDecisionService {
    BookingAcceptanceData acceptCandidate(AcceptBookingCandidateRequest request);

    BookingCandidateDecisionData rejectCandidate(RejectBookingCandidateRequest request);
}
