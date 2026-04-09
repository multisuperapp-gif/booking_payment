package com.msa.booking.payment.booking.service;

import com.msa.booking.payment.booking.dto.BookingRequestData;
import com.msa.booking.payment.booking.dto.CreateBookingRequest;
import com.msa.booking.payment.booking.dto.ExpireBookingRequestsResponse;

public interface BookingRequestService {
    BookingRequestData createRequest(CreateBookingRequest request);

    ExpireBookingRequestsResponse expireTimedOutRequests();
}
