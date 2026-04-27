package com.msa.booking.payment.order.dto;

import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateShopOrderStatusRequest(
        @NotNull(message = "order id is required")
        Long orderId,
        @NotNull(message = "status is required")
        OrderLifecycleStatus newStatus,
        Long changedByUserId,
        String reason,
        String refundPolicyApplied
) {
}
