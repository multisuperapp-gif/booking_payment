package com.msa.booking.payment.payment.service;

import java.math.BigDecimal;

public interface RazorpayGatewayService {
    RazorpayOrderData createOrder(String receipt, BigDecimal amount, String currency);

    boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature);

    boolean verifyWebhookSignature(String requestBody, String razorpaySignature);

    record RazorpayOrderData(
            String keyId,
            String orderId,
            String currency,
            BigDecimal amount,
            Long amountInPaise,
            String status
    ) {
    }
}
