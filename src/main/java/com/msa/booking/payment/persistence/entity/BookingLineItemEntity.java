package com.msa.booking.payment.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
        name = "booking_line_items",
        indexes = {
                @Index(name = "idx_booking_line_items_booking_id", columnList = "booking_id")
        }
)
@Getter
@Setter
public class BookingLineItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "service_name", nullable = false, length = 180)
    private String serviceName;

    @Column(name = "service_ref_type", nullable = false, length = 30)
    private String serviceRefType;

    @Column(name = "service_ref_id")
    private Long serviceRefId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "price_snapshot", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceSnapshot;
}
