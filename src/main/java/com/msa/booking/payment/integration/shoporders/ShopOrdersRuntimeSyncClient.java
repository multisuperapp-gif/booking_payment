package com.msa.booking.payment.integration.shoporders;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.CreateOrderRequest;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.CreatedOrderData;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderFinanceContextData;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderItemData;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.OrderStateUpdateRequest;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos.RuntimeSyncRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "shopOrdersRuntimeSyncClient",
        url = "${app.integration.shop-orders-base-url:http://127.0.0.1:8087}"
)
public interface ShopOrdersRuntimeSyncClient {
    @PostMapping("/internal/finance/orders")
    ApiResponse<CreatedOrderData> createOrder(@RequestBody CreateOrderRequest request);

    @PostMapping("/internal/finance/orders/{orderId}/runtime-sync")
    ApiResponse<Void> syncOrderRuntime(
            @PathVariable("orderId") Long orderId,
            @RequestBody RuntimeSyncRequest request
    );

    @PostMapping("/internal/finance/orders/{orderId}/movement")
    ApiResponse<Void> recordOrderMovement(
            @PathVariable("orderId") Long orderId,
            @RequestBody RuntimeSyncRequest request
    );

    @GetMapping("/internal/finance/orders/{orderId}/context")
    ApiResponse<OrderFinanceContextData> getOrderContext(@PathVariable("orderId") Long orderId);

    @GetMapping("/internal/finance/orders/{orderId}/items")
    ApiResponse<java.util.List<OrderItemData>> getOrderItems(@PathVariable("orderId") Long orderId);

    @PostMapping("/internal/finance/orders/{orderId}/state")
    ApiResponse<Void> updateOrderState(
            @PathVariable("orderId") Long orderId,
            @RequestBody OrderStateUpdateRequest request
    );
}
