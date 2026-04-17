package com.msa.booking.payment.booking.controller;

import com.msa.booking.payment.booking.dto.AcceptBookingCandidateRequest;
import com.msa.booking.payment.booking.dto.BookingAcceptanceData;
import com.msa.booking.payment.booking.dto.BookingCandidateDecisionData;
import com.msa.booking.payment.booking.dto.BookingRequestData;
import com.msa.booking.payment.booking.dto.CreateBookingRequest;
import com.msa.booking.payment.booking.dto.ExpireBookingRequestsResponse;
import com.msa.booking.payment.booking.dto.ProviderActiveBookingData;
import com.msa.booking.payment.booking.dto.ProviderPendingBookingRequestData;
import com.msa.booking.payment.booking.dto.RejectBookingCandidateRequest;
import com.msa.booking.payment.booking.dto.UserBookingRequestStatusData;
import com.msa.booking.payment.booking.service.BookingDecisionService;
import com.msa.booking.payment.booking.service.BookingRequestQueryService;
import com.msa.booking.payment.booking.service.BookingRequestService;
import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/booking-requests")
public class BookingRequestController {
    private final BookingRequestService bookingRequestService;
    private final BookingDecisionService bookingDecisionService;
    private final BookingRequestQueryService bookingRequestQueryService;

    public BookingRequestController(
            BookingRequestService bookingRequestService,
            BookingDecisionService bookingDecisionService,
            BookingRequestQueryService bookingRequestQueryService
    ) {
        this.bookingRequestService = bookingRequestService;
        this.bookingDecisionService = bookingDecisionService;
        this.bookingRequestQueryService = bookingRequestQueryService;
    }

    @GetMapping("/{requestId}")
    public ApiResponse<UserBookingRequestStatusData> userStatus(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long requestId
    ) {
        return ApiResponse.ok(bookingRequestQueryService.statusForUser(userId, requestId));
    }

    @GetMapping("/active/latest")
    public ApiResponse<UserBookingRequestStatusData> latestActiveForUser(
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ApiResponse.ok(bookingRequestQueryService.latestActiveForUser(userId));
    }

    @GetMapping("/provider/pending")
    public ApiResponse<List<ProviderPendingBookingRequestData>> providerPending(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam ProviderEntityType providerEntityType
    ) {
        return ApiResponse.ok(bookingRequestQueryService.pendingForProvider(userId, providerEntityType));
    }

    @GetMapping("/provider/active/latest")
    public ApiResponse<ProviderActiveBookingData> providerLatestActive(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam ProviderEntityType providerEntityType
    ) {
        return ApiResponse.ok(bookingRequestQueryService.latestActiveForProvider(userId, providerEntityType));
    }

    @PostMapping
    public ApiResponse<BookingRequestData> create(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CreateBookingRequest request
    ) {
        if (!userId.equals(request.userId())) {
            throw new BadRequestException("Authenticated user does not match booking request user");
        }
        return ApiResponse.success("Booking request created successfully", bookingRequestService.createRequest(request));
    }

    @PostMapping("/expire")
    public ApiResponse<ExpireBookingRequestsResponse> expireTimedOutRequests() {
        return ApiResponse.success("Expired open booking requests successfully", bookingRequestService.expireTimedOutRequests());
    }

    @PostMapping("/accept")
    public ApiResponse<BookingAcceptanceData> accept(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody AcceptBookingCandidateRequest request
    ) {
        return ApiResponse.success("Booking request accepted successfully", bookingDecisionService.acceptCandidate(userId, request));
    }

    @PostMapping("/reject")
    public ApiResponse<BookingCandidateDecisionData> reject(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody RejectBookingCandidateRequest request
    ) {
        return ApiResponse.success("Booking request candidate rejected successfully", bookingDecisionService.rejectCandidate(userId, request));
    }
}
