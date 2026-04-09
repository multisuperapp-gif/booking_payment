package com.msa.booking.payment.config;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RazorpayProperties.class)
public class RazorpayConfig {
    @Bean
    @ConditionalOnProperty(prefix = "app.razorpay", name = "enabled", havingValue = "true")
    public RazorpayClient razorpayClient(RazorpayProperties properties) throws RazorpayException {
        if (properties.getKeyId() == null || properties.getKeyId().isBlank()) {
            throw new IllegalStateException("app.razorpay.key-id must be configured when Razorpay is enabled");
        }
        if (properties.getKeySecret() == null || properties.getKeySecret().isBlank()) {
            throw new IllegalStateException("app.razorpay.key-secret must be configured when Razorpay is enabled");
        }
        return new RazorpayClient(properties.getKeyId().trim(), properties.getKeySecret().trim());
    }
}
