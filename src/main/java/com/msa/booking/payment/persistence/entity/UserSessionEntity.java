package com.msa.booking.payment.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "user_sessions")
@Getter
@Setter
public class UserSessionEntity {
    @Id
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;
}
