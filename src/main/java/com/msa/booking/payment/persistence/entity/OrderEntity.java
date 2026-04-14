package com.msa.booking.payment.persistence.entity;

import com.msa.booking.payment.domain.enums.OrderFulfillmentType;
import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_orders_user_status_created", columnList = "user_id, order_status, created_at"),
                @Index(name = "idx_orders_shop_status_created", columnList = "shop_id, order_status, created_at"),
                @Index(name = "idx_orders_location_id", columnList = "shop_location_id"),
                @Index(name = "idx_orders_address_id", columnList = "address_id")
        }
)
@Getter
@Setter
public class OrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", nullable = false, unique = true, length = 32)
    private String orderCode;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "shop_location_id")
    private Long shopLocationId;

    @Column(name = "address_id", nullable = false)
    private Long addressId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 30)
    private OrderLifecycleStatus orderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    private PayablePaymentStatus paymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_type", nullable = false, length = 20)
    private OrderFulfillmentType fulfillmentType;

    @Column(name = "subtotal_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotalAmount;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "delivery_fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal deliveryFeeAmount;

    @Column(name = "platform_fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal platformFeeAmount;

    @Column(name = "packaging_fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal packagingFeeAmount;

    @Column(name = "tip_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal tipAmount;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
