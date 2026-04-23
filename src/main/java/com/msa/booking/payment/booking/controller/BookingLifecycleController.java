package com.msa.booking.payment.booking.controller;

import com.msa.booking.payment.booking.dto.*;
import com.msa.booking.payment.booking.service.BookingLifecycleService;
import com.msa.booking.payment.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
public class BookingLifecycleController {
    private final BookingLifecycleService bookingLifecycleService;

    public BookingLifecycleController(BookingLifecycleService bookingLifecycleService) {
        this.bookingLifecycleService = bookingLifecycleService;
    }

    @GetMapping("/{bookingId}/contacts")
    public ApiResponse<BookingContactPairData> contacts(@PathVariable Long bookingId) {
        return ApiResponse.success("Booking contacts loaded successfully", bookingLifecycleService.contacts(bookingId));
    }

    @PostMapping("/{bookingId}/arrive")
    public ApiResponse<BookingLifecycleData> arrive(@PathVariable Long bookingId) {
        return ApiResponse.success("Booking marked arrived successfully", bookingLifecycleService.markArrived(bookingId));
    }

    @PostMapping("/otp/generate")
    public ApiResponse<BookingOtpData> generateOtp(@Valid @RequestBody GenerateBookingOtpRequest request) {
        return ApiResponse.success("Booking OTP generated successfully", bookingLifecycleService.generateOtp(request));
    }

    @PostMapping("/otp/verify")
    public ApiResponse<BookingLifecycleData> verifyOtp(@Valid @RequestBody VerifyBookingOtpRequest request) {
        return ApiResponse.success("Booking OTP verified successfully", bookingLifecycleService.verifyOtpAndApply(request));
    }

    @PostMapping("/cancel/user")
    public ApiResponse<BookingLifecycleData> userCancel(@Valid @RequestBody UserCancelBookingRequest request) {
        return ApiResponse.success("Booking cancellation processed successfully", bookingLifecycleService.cancelByUser(request));
    }

    @PostMapping("/review")
    public ApiResponse<BookingReviewData> submitReview(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody SubmitBookingReviewRequest request
    ) {
        return ApiResponse.success("Booking review submitted successfully", bookingLifecycleService.submitReview(userId, request));
    }
}
