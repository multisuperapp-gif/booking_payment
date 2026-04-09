package com.msa.booking.payment.persistence.entity;

import com.msa.booking.payment.domain.enums.BookingRequestCandidateStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "booking_request_candidates",
        indexes = {
                @Index(name = "idx_booking_request_candidates_request_status", columnList = "request_id, candidate_status"),
                @Index(name = "idx_booking_request_candidates_provider_status", columnList = "provider_entity_type, provider_entity_id, candidate_status")
        }
)
@Getter
@Setter
public class BookingRequestCandidateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_entity_type", nullable = false, length = 30)
    private ProviderEntityType providerEntityType;

    @Column(name = "provider_entity_id", nullable = false)
    private Long providerEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_status", nullable = false, length = 20)
    private BookingRequestCandidateStatus candidateStatus;

    @Column(name = "quoted_price_amount", precision = 12, scale = 2)
    private BigDecimal quotedPriceAmount;

    @Column(name = "distance_km", precision = 8, scale = 2)
    private BigDecimal distanceKm;

    @Column(name = "notified_at", nullable = false)
    private LocalDateTime notifiedAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
