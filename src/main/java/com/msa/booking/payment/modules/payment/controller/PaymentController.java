package com.msa.booking.payment.modules.payment.controller;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentInitiateRequest;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentInitiateResponse;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentFailureRequest;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentStatusResponse;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentVerifyRequest;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.WebhookAcknowledgeResponse;
import com.msa.booking.payment.modules.payment.service.PaymentLifecycleService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentLifecycleService paymentLifecycleService;

    public PaymentController(PaymentLifecycleService paymentLifecycleService) {
        this.paymentLifecycleService = paymentLifecycleService;
    }

    @GetMapping("/{paymentCode}")
    public ApiResponse<PaymentStatusResponse> status(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String paymentCode
    ) {
        return ApiResponse.ok(paymentLifecycleService.status(userId, paymentCode));
    }

    @PostMapping("/{paymentCode}/initiate")
    public ApiResponse<PaymentInitiateResponse> initiate(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String paymentCode,
            @RequestBody(required = false) PaymentInitiateRequest request
    ) {
        return ApiResponse.ok(paymentLifecycleService.initiate(userId, paymentCode, request));
    }

    @PostMapping("/{paymentCode}/verify")
    public ApiResponse<PaymentStatusResponse> verify(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String paymentCode,
            @Valid @RequestBody PaymentVerifyRequest request
    ) {
        return ApiResponse.ok(paymentLifecycleService.verify(userId, paymentCode, request));
    }

    @PostMapping("/{paymentCode}/failure")
    public ApiResponse<PaymentStatusResponse> failure(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String paymentCode,
            @RequestBody(required = false) PaymentFailureRequest request
    ) {
        return ApiResponse.ok(paymentLifecycleService.failure(userId, paymentCode, request));
    }

    @PostMapping(
            value = "/webhooks/razorpay",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ApiResponse<WebhookAcknowledgeResponse> razorpayWebhook(
            @RequestHeader("X-Razorpay-Signature") String signature,
            @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId,
            @RequestBody String rawPayload
    ) {
        return ApiResponse.ok(paymentLifecycleService.handleRazorpayWebhook(rawPayload, signature, eventId));
    }
}
