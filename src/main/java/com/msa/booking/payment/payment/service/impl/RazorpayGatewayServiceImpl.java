package com.msa.booking.payment.payment.service.impl;

import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.config.RazorpayProperties;
import com.msa.booking.payment.payment.service.RazorpayGatewayService;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

@Service
public class RazorpayGatewayServiceImpl implements RazorpayGatewayService {
    private final RazorpayClient razorpayClient;
    private final RazorpayProperties razorpayProperties;

    public RazorpayGatewayServiceImpl(RazorpayClient razorpayClient, RazorpayProperties razorpayProperties) {
        this.razorpayClient = razorpayClient;
        this.razorpayProperties = razorpayProperties;
    }

    @Override
    public RazorpayOrderData createOrder(String receipt, BigDecimal amount, String currency) {
        try {
            JSONObject request = new JSONObject();
            request.put("amount", toPaise(amount));
            request.put("currency", currency);
            request.put("receipt", receipt);
            request.put("payment_capture", 1);

            Order order = razorpayClient.orders.create(request);
            return new RazorpayOrderData(
                    razorpayProperties.getKeyId(),
                    order.get("id"),
                    order.get("currency"),
                    fromPaise(order.get("amount")),
                    ((Number) order.get("amount")).longValue(),
                    String.valueOf(order.get("status"))
            );
        } catch (Exception exception) {
            throw new BadRequestException("Unable to create Razorpay order: " + exception.getMessage());
        }
    }

    @Override
    public String configuredKeyId() {
        return razorpayProperties.getKeyId();
    }

    @Override
    public boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
        try {
            String payload = razorpayOrderId + "|" + razorpayPaymentId;
            String expectedSignature = hmacSha256Hex(payload, razorpayProperties.getKeySecret());
            return expectedSignature.equals(razorpaySignature);
        } catch (Exception exception) {
            throw new BadRequestException("Unable to verify Razorpay payment signature.");
        }
    }

    @Override
    public boolean verifyWebhookSignature(String requestBody, String razorpaySignature) {
        String webhookSecret = razorpayProperties.getWebhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new BadRequestException("Razorpay webhook secret is not configured.");
        }
        try {
            String expectedSignature = hmacSha256Hex(requestBody, webhookSecret);
            return expectedSignature.equals(razorpaySignature);
        } catch (Exception exception) {
            throw new BadRequestException("Unable to verify Razorpay webhook signature.");
        }
    }

    private String hmacSha256Hex(String payload, String secret) throws Exception {
        Mac sha256Hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );
        sha256Hmac.init(secretKey);
        byte[] hash = sha256Hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return toHex(hash);
    }

    private long toPaise(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private BigDecimal fromPaise(Object paiseValue) {
        long paise = ((Number) paiseValue).longValue();
        return BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
