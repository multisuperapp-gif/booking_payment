package com.msa.booking.payment.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "booking_status_history",
        indexes = {
                @Index(name = "idx_booking_status_history_booking_id", columnList = "booking_id")
        }
)
@Getter
@Setter
public class BookingStatusHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "old_status", length = 50)
    private String oldStatus;

    @Column(name = "new_status", nullable = false, length = 50)
    private String newStatus;

    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "refund_policy_applied", length = 120)
    private String refundPolicyApplied;

    @Column(name = "changed_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime changedAt;
}
