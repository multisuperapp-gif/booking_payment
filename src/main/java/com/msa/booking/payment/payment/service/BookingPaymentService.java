package com.msa.booking.payment.payment.service;

import com.msa.booking.payment.payment.dto.BookingPaymentData;
import com.msa.booking.payment.payment.dto.CompleteBookingPaymentRequest;
import com.msa.booking.payment.payment.dto.InitiateBookingPaymentRequest;

public interface BookingPaymentService {
    BookingPaymentData initiate(InitiateBookingPaymentRequest request);

    BookingPaymentData markSuccess(CompleteBookingPaymentRequest request);

    BookingPaymentData markFailure(CompleteBookingPaymentRequest request);
}
