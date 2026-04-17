package com.msa.booking.payment.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record PushTokenRegisterRequest(
        Long userDeviceId,
        String deviceToken,
        @NotBlank(message = "platform is required")
        String platform,
        @NotBlank(message = "pushProvider is required")
        String pushProvider,
        @NotBlank(message = "pushToken is required")
        String pushToken
) {
}
