package com.msa.booking.payment.modules.payment.service;

import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.config.RazorpayProperties;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class RazorpaySignatureService {
    private final RazorpayProperties razorpayProperties;

    public RazorpaySignatureService(RazorpayProperties razorpayProperties) {
        this.razorpayProperties = razorpayProperties;
    }

    public void verifyPaymentSignature(String gatewayOrderId, String gatewayPaymentId, String providedSignature) {
        String secret = razorpayProperties.getKeySecret();
        if (secret == null || secret.isBlank()) {
            throw new BadRequestException("Razorpay secret is not configured");
        }
        String payload = gatewayOrderId + "|" + gatewayPaymentId;
        String expectedSignature = hmacSha256Hex(payload, secret);
        if (!expectedSignature.equals(providedSignature)) {
            throw new BadRequestException("Invalid Razorpay payment signature");
        }
    }

    public void verifyWebhookSignature(String rawPayload, String providedSignature) {
        String secret = razorpayProperties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new BadRequestException("Razorpay webhook secret is not configured");
        }
        String expectedSignature = hmacSha256Hex(rawPayload, secret);
        if (!expectedSignature.equals(providedSignature)) {
            throw new BadRequestException("Invalid Razorpay webhook signature");
        }
    }

    private static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute Razorpay signature", exception);
        }
    }
}
