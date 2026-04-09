package com.msa.booking.payment.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "push_notification_tokens")
@Getter
@Setter
public class PushNotificationTokenEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_device_id")
    private Long userDeviceId;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @Column(name = "push_provider", nullable = false, length = 20)
    private String pushProvider;

    @Column(name = "push_token", nullable = false, unique = true, length = 255)
    private String pushToken;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
