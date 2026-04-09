package com.msa.booking.payment.order.projection;

import java.math.BigDecimal;

public interface ShopCheckoutItemProjection {
    Long getVariantId();

    Long getProductId();

    Long getShopId();

    Long getShopOwnerUserId();

    Long getShopLocationId();

    String getProductName();

    String getVariantName();

    BigDecimal getSellingPrice();

    Boolean getProductActive();

    Integer getQuantityAvailable();

    Integer getReservedQuantity();

    String getInventoryStatus();
}
