package com.msa.booking.payment.booking.service;

import com.msa.booking.payment.booking.dto.*;

public interface BookingLifecycleService {
    BookingContactPairData contacts(Long bookingId);

    BookingLifecycleData markArrived(Long bookingId);

    BookingOtpData generateOtp(GenerateBookingOtpRequest request);

    BookingLifecycleData verifyOtpAndApply(VerifyBookingOtpRequest request);

    BookingLifecycleData cancelByUser(UserCancelBookingRequest request);

    BookingReviewData submitReview(Long actingUserId, SubmitBookingReviewRequest request);
}
