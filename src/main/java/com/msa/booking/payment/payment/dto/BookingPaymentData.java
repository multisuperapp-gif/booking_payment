package com.msa.booking.payment.payment.dto;

import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;

import java.math.BigDecimal;

public record BookingPaymentData(
        Long bookingId,
        String bookingCode,
        String paymentCode,
        String gatewayName,
        String razorpayKeyId,
        String razorpayOrderId,
        PaymentLifecycleStatus paymentLifecycleStatus,
        BookingLifecycleStatus bookingStatus,
        PayablePaymentStatus payablePaymentStatus,
        BigDecimal amount,
        String currencyCode,
        Long amountInPaise
) {
}
