package com.msa.booking.payment.domain.enums;

public enum OrderLifecycleStatus {
    CREATED,
    PAYMENT_PENDING,
    PAYMENT_COMPLETED,
    ACCEPTED,
    PREPARING,
    DISPATCHED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    RETURN_REQUESTED,
    RETURNED
}
