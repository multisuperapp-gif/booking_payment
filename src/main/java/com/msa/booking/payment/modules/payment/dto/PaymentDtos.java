package com.msa.booking.payment.modules.payment.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class PaymentDtos {
    private PaymentDtos() {
    }

    public record PaymentInitiateRequest(
            String gatewayName
    ) {
    }

    public record PaymentVerifyRequest(
            @NotBlank String gatewayOrderId,
            @NotBlank String gatewayPaymentId,
            @NotBlank String razorpaySignature
    ) {
    }

    public record PaymentFailureRequest(
            String gatewayOrderId,
            String failureCode,
            String failureMessage
    ) {
    }

    public record PaymentStatusResponse(
            Long paymentId,
            String paymentCode,
            String payableType,
            Long payableId,
            String paymentStatus,
            BigDecimal amount,
            String currencyCode,
            String gatewayName,
            String gatewayOrderId,
            String latestAttemptStatus,
            String latestGatewayTransactionId,
            LocalDateTime initiatedAt,
            LocalDateTime completedAt,
            String invoiceObjectKey,
            String invoiceAccessUrl
    ) {
    }

    public record PaymentInitiateResponse(
            Long paymentId,
            String paymentCode,
            String gatewayName,
            String gatewayOrderId,
            String gatewayKeyId,
            BigDecimal amount,
            String currencyCode,
            String paymentStatus,
            String payableType,
            Long payableId
    ) {
    }

    public record WebhookAcknowledgeResponse(
            boolean processed,
            String eventType,
            String message
    ) {
    }
}
