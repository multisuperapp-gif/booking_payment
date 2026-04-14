package com.msa.booking.payment.modules.payment.service;

import com.msa.booking.payment.persistence.entity.PaymentWebhookEventEntity;
import com.msa.booking.payment.persistence.repository.PaymentWebhookEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentWebhookEventService {
    private final PaymentWebhookEventRepository paymentWebhookEventRepository;

    public PaymentWebhookEventService(PaymentWebhookEventRepository paymentWebhookEventRepository) {
        this.paymentWebhookEventRepository = paymentWebhookEventRepository;
    }

    public String resolveWebhookKey(String eventIdHeader, String rawPayload) {
        if (eventIdHeader != null && !eventIdHeader.isBlank()) {
            return eventIdHeader.trim();
        }
        return sha256(rawPayload == null ? "" : rawPayload);
    }

    @Transactional
    public boolean registerIfFirst(
            String sourceSystem,
            String webhookKey,
            String eventType,
            String gatewayOrderId,
            String gatewayPaymentId,
            Long paymentId
    ) {
        PaymentWebhookEventEntity event = new PaymentWebhookEventEntity();
        event.setSourceSystem(sourceSystem);
        event.setWebhookKey(webhookKey);
        event.setEventType(eventType);
        event.setGatewayOrderId(gatewayOrderId);
        event.setGatewayPaymentId(gatewayPaymentId);
        event.setPaymentId(paymentId);
        event.setProcessed(false);
        event.setReceivedAt(LocalDateTime.now());
        try {
            paymentWebhookEventRepository.save(event);
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    @Transactional
    public void markProcessed(String sourceSystem, String webhookKey, boolean processed, String message) {
        paymentWebhookEventRepository.findBySourceSystemAndWebhookKey(sourceSystem, webhookKey).ifPresent(event -> {
            event.setProcessed(processed);
            event.setMessage(message);
            event.setProcessedAt(LocalDateTime.now());
            paymentWebhookEventRepository.save(event);
        });
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }
}
