package com.msa.booking.payment.notification.dto;

import java.time.LocalDateTime;

public record PushTokenData(
        Long id,
        Long userId,
        Long userDeviceId,
        String platform,
        String pushProvider,
        String appContext,
        String pushToken,
        boolean active,
        LocalDateTime lastSeenAt
) {
}
