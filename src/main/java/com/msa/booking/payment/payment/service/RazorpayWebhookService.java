package com.msa.booking.payment.payment.service;

public interface RazorpayWebhookService {
    void processWebhook(String requestBody, String razorpaySignature);
}
