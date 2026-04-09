package com.msa.booking.payment.order.projection;

import java.math.BigDecimal;

public interface ShopDeliveryRuleProjection {
    Long getShopLocationId();

    Long getOwnerUserId();

    String getDeliveryType();

    BigDecimal getMinOrderAmount();

    BigDecimal getDeliveryFee();

    BigDecimal getFreeDeliveryAbove();
}
