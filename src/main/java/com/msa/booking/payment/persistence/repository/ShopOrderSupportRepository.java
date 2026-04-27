package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.order.projection.ShopCheckoutItemProjection;
import com.msa.booking.payment.order.projection.ShopDeliveryRuleProjection;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShopOrderSupportRepository extends Repository<PaymentEntity, Long> {
    @Query(value = """
            SELECT
                pv.id AS variantId,
                p.id AS productId,
                p.shop_id AS shopId,
                s.owner_user_id AS shopOwnerUserId,
                sl.id AS shopLocationId,
                p.name AS productName,
                pv.variant_name AS variantName,
                pv.selling_price AS sellingPrice,
                p.is_active AS productActive,
                COALESCE(i.quantity_available, 0) AS quantityAvailable,
                COALESCE(i.reserved_quantity, 0) AS reservedQuantity,
                COALESCE(i.inventory_status, 'OUT_OF_STOCK') AS inventoryStatus
            FROM product_variants pv
            JOIN products p ON p.id = pv.product_id
            JOIN shops s ON s.id = p.shop_id
            LEFT JOIN inventory i ON i.variant_id = pv.id
            LEFT JOIN shop_locations sl
                ON sl.shop_id = p.shop_id
               AND sl.is_primary = 1
               AND sl.approval_status = 'APPROVED'
            WHERE pv.id IN (:variantIds)
            """, nativeQuery = true)
    List<ShopCheckoutItemProjection> findCheckoutItemsByVariantIds(@Param("variantIds") Collection<Long> variantIds);

    @Query(value = """
            SELECT
                sl.id AS shopLocationId,
                s.owner_user_id AS ownerUserId,
                COALESCE(sdr.delivery_type, 'PICKUP_ONLY') AS deliveryType,
                COALESCE(sdr.min_order_amount, 0) AS minOrderAmount,
                COALESCE(sdr.delivery_fee, 0) AS deliveryFee,
                sdr.free_delivery_above AS freeDeliveryAbove
            FROM shops s
            JOIN shop_locations sl
                ON sl.shop_id = s.id
               AND sl.is_primary = 1
               AND sl.approval_status = 'APPROVED'
            LEFT JOIN shop_delivery_rules sdr ON sdr.shop_location_id = sl.id
            WHERE s.id = :shopId
            LIMIT 1
            """, nativeQuery = true)
    Optional<ShopDeliveryRuleProjection> findPrimaryDeliveryRuleByShopId(@Param("shopId") Long shopId);

    @Query(value = "SELECT owner_user_id FROM shops WHERE id = :shopId", nativeQuery = true)
    Optional<Long> findShopOwnerUserId(@Param("shopId") Long shopId);

    @Modifying
    @Query(value = """
            UPDATE inventory
            SET reserved_quantity = reserved_quantity + :quantity
            WHERE variant_id = :variantId
              AND inventory_status IN ('IN_STOCK', 'LOW_STOCK')
              AND (quantity_available - reserved_quantity) >= :quantity
            """, nativeQuery = true)
    int reserveInventory(@Param("variantId") Long variantId, @Param("quantity") Integer quantity);

    @Modifying
    @Query(value = """
            UPDATE inventory
            SET reserved_quantity = CASE
                    WHEN reserved_quantity >= :quantity THEN reserved_quantity - :quantity
                    ELSE 0
                END
            WHERE variant_id = :variantId
            """, nativeQuery = true)
    int releaseReservedInventory(@Param("variantId") Long variantId, @Param("quantity") Integer quantity);

    @Modifying
    @Query(value = """
            UPDATE inventory
            SET quantity_available = quantity_available - :quantity,
                reserved_quantity = reserved_quantity - :quantity
            WHERE variant_id = :variantId
              AND reserved_quantity >= :quantity
              AND quantity_available >= :quantity
            """, nativeQuery = true)
    int consumeReservedInventory(@Param("variantId") Long variantId, @Param("quantity") Integer quantity);

    @Modifying
    @Query(value = """
            UPDATE inventory
            SET quantity_available = quantity_available + :quantity
            WHERE variant_id = :variantId
            """, nativeQuery = true)
    int restockInventory(@Param("variantId") Long variantId, @Param("quantity") Integer quantity);
}
