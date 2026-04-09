package com.msa.booking.payment.order.service;

import com.msa.booking.payment.order.dto.*;

public interface ShopOrderService {
    ShopOrderData createOrder(CreateShopOrderRequest request);

    ShopOrderData initiatePayment(InitiateShopOrderPaymentRequest request);

    ShopOrderData markPaymentSuccess(CompleteShopOrderPaymentRequest request);

    ShopOrderData markPaymentFailure(CompleteShopOrderPaymentRequest request);

    ShopOrderData updateStatus(UpdateShopOrderStatusRequest request);

    ShopOrderData cancelByUser(CancelShopOrderRequest request);
}
