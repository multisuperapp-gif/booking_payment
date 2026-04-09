package com.msa.booking.payment.payment.controller;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.payment.dto.BookingPaymentData;
import com.msa.booking.payment.payment.dto.CompleteBookingPaymentRequest;
import com.msa.booking.payment.payment.dto.InitiateBookingPaymentRequest;
import com.msa.booking.payment.payment.service.BookingPaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/booking-payments")
public class BookingPaymentController {
    private final BookingPaymentService bookingPaymentService;

    public BookingPaymentController(BookingPaymentService bookingPaymentService) {
        this.bookingPaymentService = bookingPaymentService;
    }

    @PostMapping("/initiate")
    public ApiResponse<BookingPaymentData> initiate(@Valid @RequestBody InitiateBookingPaymentRequest request) {
        return ApiResponse.success("Booking payment initiated successfully", bookingPaymentService.initiate(request));
    }

    @PostMapping("/success")
    public ApiResponse<BookingPaymentData> markSuccess(@Valid @RequestBody CompleteBookingPaymentRequest request) {
        return ApiResponse.success("Booking payment marked successful", bookingPaymentService.markSuccess(request));
    }

    @PostMapping("/failure")
    public ApiResponse<BookingPaymentData> markFailure(@Valid @RequestBody CompleteBookingPaymentRequest request) {
        return ApiResponse.success("Booking payment marked failed", bookingPaymentService.markFailure(request));
    }
}
