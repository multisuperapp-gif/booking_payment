package com.msa.booking.payment.persistence.entity;

import com.msa.booking.payment.domain.enums.BookingActionOtpStatus;
import com.msa.booking.payment.domain.enums.BookingOtpPurpose;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "booking_action_otps",
        indexes = {
                @Index(name = "idx_booking_action_otps_booking_purpose_status", columnList = "booking_id, otp_purpose, otp_status")
        }
)
@Getter
@Setter
public class BookingActionOtpEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "otp_purpose", nullable = false, length = 30)
    private BookingOtpPurpose otpPurpose;

    @Column(name = "otp_code", nullable = false, length = 6)
    private String otpCode;

    @Column(name = "issued_to_user_id", nullable = false)
    private Long issuedToUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "otp_status", nullable = false, length = 20)
    private BookingActionOtpStatus otpStatus;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
