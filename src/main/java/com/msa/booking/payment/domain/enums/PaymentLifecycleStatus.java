package com.msa.booking.payment.domain.enums;

public enum PaymentLifecycleStatus {
    INITIATED,
    PENDING,
    SUCCESS,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED
}
