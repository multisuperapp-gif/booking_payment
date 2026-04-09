package com.msa.booking.payment.booking.service;

import com.msa.booking.payment.booking.dto.BookingRequestCandidateInput;
import com.msa.booking.payment.booking.dto.CreateBookingRequest;

import java.util.List;

public interface BookingCandidateMatchingService {
    BookingRequestCandidateInput resolveDirectCandidate(CreateBookingRequest request);

    List<BookingRequestCandidateInput> resolveBroadcastCandidates(CreateBookingRequest request);
}
