package com.msa.booking.payment.order.dto;

import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;

import java.math.BigDecimal;

public record ShopOrderData(
        Long orderId,
        String orderCode,
        Long shopId,
        OrderLifecycleStatus orderStatus,
        PayablePaymentStatus paymentStatus,
        String paymentCode,
        String gatewayName,
        String razorpayKeyId,
        String razorpayOrderId,
        BigDecimal subtotalAmount,
        BigDecimal deliveryFeeAmount,
        BigDecimal platformFeeAmount,
        BigDecimal totalAmount,
        String currencyCode,
        Long amountInPaise,
        String note
) {
}
