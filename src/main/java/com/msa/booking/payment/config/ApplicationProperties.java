package com.msa.booking.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record ApplicationProperties(
        Security security,
        RateLimit rateLimit
) {
    public record Security(
            String accessTokenSecret,
            long accessTokenValiditySeconds,
            long refreshTokenValiditySeconds
    ) {
    }

    public record RateLimit(
            boolean enabled,
            long windowSeconds,
            int paymentInitiateMaxRequests,
            int paymentVerifyMaxRequests,
            int paymentFailureMaxRequests,
            int paymentStatusMaxRequests,
            int orderCancelMaxRequests
    ) {
    }
}
