package com.msa.booking.payment.notification.controller;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.notification.dto.PushTokenData;
import com.msa.booking.payment.notification.dto.PushTokenDeactivateRequest;
import com.msa.booking.payment.notification.dto.PushTokenRegisterRequest;
import com.msa.booking.payment.notification.service.PushTokenService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/booking-notifications/push-tokens")
public class PushTokenController {
    private final PushTokenService pushTokenService;

    public PushTokenController(PushTokenService pushTokenService) {
        this.pushTokenService = pushTokenService;
    }

    @PostMapping
    public ApiResponse<PushTokenData> register(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody PushTokenRegisterRequest request
    ) {
        return ApiResponse.success("Push token registered successfully", pushTokenService.register(userId, request));
    }

    @PatchMapping("/deactivate")
    public ApiResponse<Void> deactivate(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody PushTokenDeactivateRequest request
    ) {
        pushTokenService.deactivate(userId, request);
        return ApiResponse.success("Push token deactivated successfully", null);
    }
}
