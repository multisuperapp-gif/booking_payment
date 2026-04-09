package com.msa.booking.payment.persistence.entity;

import com.msa.booking.payment.domain.enums.SuspensionEntityType;
import com.msa.booking.payment.domain.enums.SuspensionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "suspensions",
        indexes = {
                @Index(name = "idx_suspensions_user_id", columnList = "user_id"),
                @Index(name = "idx_suspensions_entity", columnList = "entity_type, entity_id"),
                @Index(name = "idx_suspensions_status", columnList = "status")
        }
)
@Getter
@Setter
public class SuspensionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private SuspensionEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SuspensionStatus status;
}
