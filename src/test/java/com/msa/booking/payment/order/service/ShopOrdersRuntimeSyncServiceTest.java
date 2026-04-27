package com.msa.booking.payment.order.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.integration.shoporders.ShopOrdersRuntimeSyncClient;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.RuntimeSyncRequest;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopOrdersRuntimeSyncServiceTest {

    @Mock
    private ShopOrdersRuntimeSyncClient shopOrdersRuntimeSyncClient;

    private ShopOrdersRuntimeSyncService service;

    @BeforeEach
    void setUp() {
        service = new ShopOrdersRuntimeSyncService(shopOrdersRuntimeSyncClient);
    }

    @Test
    void syncOrderAfterCommitDelegatesToShopOrdersClient() {
        when(shopOrdersRuntimeSyncClient.syncOrderRuntime(eq(44L), any(RuntimeSyncRequest.class)))
                .thenReturn(ApiResponse.success("ok", null));

        service.syncOrderAfterCommit(44L, "CONSUME", "Payment complete.");

        verify(shopOrdersRuntimeSyncClient).syncOrderRuntime(
                eq(44L),
                eq(new RuntimeSyncRequest("CONSUME", "Payment complete."))
        );
    }

    @Test
    void syncOrderAfterCommitIgnoresFeignFailures() {
        when(shopOrdersRuntimeSyncClient.syncOrderRuntime(eq(44L), any(RuntimeSyncRequest.class)))
                .thenThrow(FeignException.errorStatus(
                        "syncOrderRuntime",
                        feign.Response.builder()
                                .status(502)
                                .reason("Bad Gateway")
                                .request(Request.create(Request.HttpMethod.POST, "/internal/finance/orders/44/runtime-sync", Map.of(), null, StandardCharsets.UTF_8, new RequestTemplate()))
                                .build()
                ));

        Logger logger = (Logger) LoggerFactory.getLogger(ShopOrdersRuntimeSyncService.class);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.ERROR);
        try {
            service.syncOrderAfterCommit(44L, "RELEASE", "Payment failed.");
        } finally {
            logger.setLevel(originalLevel);
        }

        verify(shopOrdersRuntimeSyncClient).syncOrderRuntime(
                eq(44L),
                eq(new RuntimeSyncRequest("RELEASE", "Payment failed."))
        );
    }

    @Test
    void syncOrderAfterCommitSkipsNullOrderId() {
        service.syncOrderAfterCommit(null, "CONSUME", "ignored");

        verify(shopOrdersRuntimeSyncClient, never()).syncOrderRuntime(any(), any());
    }
}
