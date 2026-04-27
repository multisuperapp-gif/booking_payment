package com.msa.booking.payment.order.service;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.common.exception.ResourceNotFoundException;
import com.msa.booking.payment.integration.shoporders.ShopOrdersRuntimeSyncClient;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderFinanceContextData;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderItemData;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderStateUpdateRequest;
import feign.FeignException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ShopOrderFinanceContextService {
    private final ShopOrdersRuntimeSyncClient shopOrdersRuntimeSyncClient;

    public ShopOrderFinanceContextService(ShopOrdersRuntimeSyncClient shopOrdersRuntimeSyncClient) {
        this.shopOrdersRuntimeSyncClient = shopOrdersRuntimeSyncClient;
    }

    public OrderFinanceContextData loadRequired(Long orderId) {
        try {
            ApiResponse<OrderFinanceContextData> response = shopOrdersRuntimeSyncClient.getOrderContext(orderId);
            if (response == null || !response.success() || response.data() == null) {
                throw new ResourceNotFoundException("Linked order not found");
            }
            return response.data();
        } catch (FeignException.NotFound exception) {
            throw new ResourceNotFoundException("Linked order not found");
        } catch (FeignException exception) {
            throw new ResourceNotFoundException("Unable to load linked order context");
        }
    }

    public List<OrderItemData> loadItemsRequired(Long orderId) {
        try {
            ApiResponse<List<OrderItemData>> response = shopOrdersRuntimeSyncClient.getOrderItems(orderId);
            if (response == null || !response.success() || response.data() == null) {
                throw new ResourceNotFoundException("Linked order items not found");
            }
            return response.data();
        } catch (FeignException.NotFound exception) {
            throw new ResourceNotFoundException("Linked order not found");
        } catch (FeignException exception) {
            throw new ResourceNotFoundException("Unable to load linked order items");
        }
    }

    public void updateStateRequired(
            Long orderId,
            String paymentStatus,
            String orderStatus,
            Long changedByUserId,
            String reason,
            String refundPolicyApplied
    ) {
        if (orderId == null) {
            throw new ResourceNotFoundException("Linked order not found");
        }
        Runnable action = () -> doUpdateState(orderId, paymentStatus, orderStatus, changedByUserId, reason, refundPolicyApplied);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private void doUpdateState(
            Long orderId,
            String paymentStatus,
            String orderStatus,
            Long changedByUserId,
            String reason,
            String refundPolicyApplied
    ) {
        try {
            ApiResponse<Void> response = shopOrdersRuntimeSyncClient.updateOrderState(
                    orderId,
                    new OrderStateUpdateRequest(paymentStatus, orderStatus, changedByUserId, reason, refundPolicyApplied)
            );
            if (response == null || !response.success()) {
                throw new ResourceNotFoundException("Unable to update linked order state");
            }
        } catch (FeignException.NotFound exception) {
            throw new ResourceNotFoundException("Linked order not found");
        } catch (FeignException exception) {
            throw new ResourceNotFoundException("Unable to update linked order state");
        }
    }
}
