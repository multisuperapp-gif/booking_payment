package com.msa.booking.payment.payment.controller;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.payment.service.RazorpayWebhookService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/razorpay")
public class RazorpayWebhookController {
    private final RazorpayWebhookService razorpayWebhookService;

    public RazorpayWebhookController(RazorpayWebhookService razorpayWebhookService) {
        this.razorpayWebhookService = razorpayWebhookService;
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> webhook(
            @RequestBody String requestBody,
            @RequestHeader(name = "X-Razorpay-Signature") String razorpaySignature
    ) {
        razorpayWebhookService.processWebhook(requestBody, razorpaySignature);
        return ApiResponse.success("Razorpay webhook processed successfully", null);
    }
}
