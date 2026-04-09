package com.msa.booking.payment.booking.controller;

import com.msa.booking.payment.booking.dto.BookingRequestData;
import com.msa.booking.payment.booking.dto.CreateBookingRequest;
import com.msa.booking.payment.booking.dto.ExpireBookingRequestsResponse;
import com.msa.booking.payment.booking.dto.AcceptBookingCandidateRequest;
import com.msa.booking.payment.booking.dto.BookingAcceptanceData;
import com.msa.booking.payment.booking.dto.BookingCandidateDecisionData;
import com.msa.booking.payment.booking.dto.RejectBookingCandidateRequest;
import com.msa.booking.payment.booking.service.BookingDecisionService;
import com.msa.booking.payment.booking.service.BookingRequestService;
import com.msa.booking.payment.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/booking-requests")
public class BookingRequestController {
    private final BookingRequestService bookingRequestService;
    private final BookingDecisionService bookingDecisionService;

    public BookingRequestController(
            BookingRequestService bookingRequestService,
            BookingDecisionService bookingDecisionService
    ) {
        this.bookingRequestService = bookingRequestService;
        this.bookingDecisionService = bookingDecisionService;
    }

    @PostMapping
    public ApiResponse<BookingRequestData> create(@Valid @RequestBody CreateBookingRequest request) {
        return ApiResponse.success("Booking request created successfully", bookingRequestService.createRequest(request));
    }

    @PostMapping("/expire")
    public ApiResponse<ExpireBookingRequestsResponse> expireTimedOutRequests() {
        return ApiResponse.success("Expired open booking requests successfully", bookingRequestService.expireTimedOutRequests());
    }

    @PostMapping("/accept")
    public ApiResponse<BookingAcceptanceData> accept(@Valid @RequestBody AcceptBookingCandidateRequest request) {
        return ApiResponse.success("Booking request accepted successfully", bookingDecisionService.acceptCandidate(request));
    }

    @PostMapping("/reject")
    public ApiResponse<BookingCandidateDecisionData> reject(@Valid @RequestBody RejectBookingCandidateRequest request) {
        return ApiResponse.success("Booking request candidate rejected successfully", bookingDecisionService.rejectCandidate(request));
    }
}
