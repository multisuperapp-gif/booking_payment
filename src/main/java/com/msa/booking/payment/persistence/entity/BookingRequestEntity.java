package com.msa.booking.payment.persistence.entity;

import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingRequestMode;
import com.msa.booking.payment.domain.enums.BookingRequestStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "booking_requests",
        indexes = {
                @Index(name = "idx_booking_requests_user_status_created", columnList = "user_id, request_status, created_at"),
                @Index(name = "idx_booking_requests_target_provider", columnList = "target_provider_entity_type, target_provider_entity_id"),
                @Index(name = "idx_booking_requests_expires_at", columnList = "expires_at")
        }
)
@Getter
@Setter
public class BookingRequestEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_code", nullable = false, length = 32, unique = true)
    private String requestCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_type", nullable = false, length = 20)
    private BookingFlowType bookingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_mode", nullable = false, length = 20)
    private BookingRequestMode requestMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_status", nullable = false, length = 30)
    private BookingRequestStatus requestStatus;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "address_id", nullable = false)
    private Long addressId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_provider_entity_type", length = 30)
    private ProviderEntityType targetProviderEntityType;

    @Column(name = "target_provider_entity_id")
    private Long targetProviderEntityId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "subcategory_id")
    private Long subcategoryId;

    @Column(name = "scheduled_start_at", nullable = false)
    private LocalDateTime scheduledStartAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "price_min_amount", precision = 12, scale = 2)
    private BigDecimal priceMinAmount;

    @Column(name = "price_max_amount", precision = 12, scale = 2)
    private BigDecimal priceMaxAmount;

    @Column(name = "search_latitude", precision = 10, scale = 7)
    private BigDecimal searchLatitude;

    @Column(name = "search_longitude", precision = 10, scale = 7)
    private BigDecimal searchLongitude;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
