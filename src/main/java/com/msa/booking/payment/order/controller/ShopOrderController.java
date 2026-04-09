package com.msa.booking.payment.order.controller;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.order.dto.*;
import com.msa.booking.payment.order.service.ShopOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shop-orders")
public class ShopOrderController {
    private final ShopOrderService shopOrderService;

    public ShopOrderController(ShopOrderService shopOrderService) {
        this.shopOrderService = shopOrderService;
    }

    @PostMapping
    public ApiResponse<ShopOrderData> create(@Valid @RequestBody CreateShopOrderRequest request) {
        return ApiResponse.success("Shop order created successfully", shopOrderService.createOrder(request));
    }

    @PostMapping("/payments/initiate")
    public ApiResponse<ShopOrderData> initiatePayment(@Valid @RequestBody InitiateShopOrderPaymentRequest request) {
        return ApiResponse.success("Shop order payment initiated successfully", shopOrderService.initiatePayment(request));
    }

    @PostMapping("/payments/success")
    public ApiResponse<ShopOrderData> markPaymentSuccess(@Valid @RequestBody CompleteShopOrderPaymentRequest request) {
        return ApiResponse.success("Shop order payment completed successfully", shopOrderService.markPaymentSuccess(request));
    }

    @PostMapping("/payments/failure")
    public ApiResponse<ShopOrderData> markPaymentFailure(@Valid @RequestBody CompleteShopOrderPaymentRequest request) {
        return ApiResponse.success("Shop order payment failed", shopOrderService.markPaymentFailure(request));
    }

    @PostMapping("/status")
    public ApiResponse<ShopOrderData> updateStatus(@Valid @RequestBody UpdateShopOrderStatusRequest request) {
        return ApiResponse.success("Shop order status updated successfully", shopOrderService.updateStatus(request));
    }

    @PostMapping("/cancel")
    public ApiResponse<ShopOrderData> cancelByUser(@Valid @RequestBody CancelShopOrderRequest request) {
        return ApiResponse.success("Shop order cancelled successfully", shopOrderService.cancelByUser(request));
    }
}
