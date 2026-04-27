package com.msa.booking.payment.order.service;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.integration.shoporders.ShopOrdersRuntimeSyncClient;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.RuntimeSyncRequest;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ShopOrdersRuntimeSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShopOrdersRuntimeSyncService.class);

    private final ShopOrdersRuntimeSyncClient shopOrdersRuntimeSyncClient;

    public ShopOrdersRuntimeSyncService(ShopOrdersRuntimeSyncClient shopOrdersRuntimeSyncClient) {
        this.shopOrdersRuntimeSyncClient = shopOrdersRuntimeSyncClient;
    }

    public void syncOrderAfterCommit(Long orderId, String movementType, String movementReason) {
        if (orderId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    syncOrderRuntime(orderId, movementType, movementReason);
                }
            });
            return;
        }
        syncOrderRuntime(orderId, movementType, movementReason);
    }

    public void recordOrderMovementAfterCommit(Long orderId, String movementType, String movementReason) {
        if (orderId == null || movementType == null || movementType.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recordOrderMovement(orderId, movementType, movementReason);
                }
            });
            return;
        }
        recordOrderMovement(orderId, movementType, movementReason);
    }

    private void syncOrderRuntime(Long orderId, String movementType, String movementReason) {
        try {
            ApiResponse<Void> response = shopOrdersRuntimeSyncClient.syncOrderRuntime(
                    orderId,
                    new RuntimeSyncRequest(movementType, movementReason)
            );
            if (response == null || !response.success()) {
                LOGGER.warn("Shop runtime sync returned unsuccessful response. orderId={} message={}",
                        orderId,
                        response == null ? null : response.message());
            }
        } catch (FeignException exception) {
            LOGGER.warn("Failed to sync shop runtime after finance event. orderId={}", orderId, exception);
        }
    }

    private void recordOrderMovement(Long orderId, String movementType, String movementReason) {
        try {
            ApiResponse<Void> response = shopOrdersRuntimeSyncClient.recordOrderMovement(
                    orderId,
                    new RuntimeSyncRequest(movementType, movementReason)
            );
            if (response == null || !response.success()) {
                LOGGER.warn("Shop movement record returned unsuccessful response. orderId={} message={}",
                        orderId,
                        response == null ? null : response.message());
            }
        } catch (FeignException exception) {
            LOGGER.warn("Failed to record shop inventory movement after finance event. orderId={}", orderId, exception);
        }
    }
}
