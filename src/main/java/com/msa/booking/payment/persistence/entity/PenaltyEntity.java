package com.msa.booking.payment.persistence.entity;

import com.msa.booking.payment.domain.enums.PenaltyEntityType;
import com.msa.booking.payment.domain.enums.PenaltyType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "penalties",
        indexes = {
                @Index(name = "idx_penalties_user_id", columnList = "penalized_user_id"),
                @Index(name = "idx_penalties_entity", columnList = "entity_type, entity_id")
        }
)
@Getter
@Setter
public class PenaltyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "penalized_user_id", nullable = false)
    private Long penalizedUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private PenaltyEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", nullable = false, length = 20)
    private PenaltyType penaltyType;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;
}
