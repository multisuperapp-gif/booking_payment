package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.BookingActionOtpStatus;
import com.msa.booking.payment.domain.enums.BookingOtpPurpose;
import com.msa.booking.payment.persistence.entity.BookingActionOtpEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingActionOtpRepository extends JpaRepository<BookingActionOtpEntity, Long> {
    List<BookingActionOtpEntity> findByBookingIdAndOtpPurposeAndOtpStatus(Long bookingId, BookingOtpPurpose otpPurpose, BookingActionOtpStatus otpStatus);

    Optional<BookingActionOtpEntity> findFirstByBookingIdAndOtpPurposeAndOtpCodeAndOtpStatusOrderByIdDesc(
            Long bookingId,
            BookingOtpPurpose otpPurpose,
            String otpCode,
            BookingActionOtpStatus otpStatus
    );
}
