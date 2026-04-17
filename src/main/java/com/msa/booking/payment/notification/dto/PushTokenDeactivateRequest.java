package com.msa.booking.payment.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record PushTokenDeactivateRequest(
        @NotBlank(message = "pushToken is required")
        String pushToken
) {
}
