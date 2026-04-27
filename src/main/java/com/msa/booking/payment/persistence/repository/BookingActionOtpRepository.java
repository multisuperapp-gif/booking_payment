package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.BookingActionOtpStatus;
import com.msa.booking.payment.domain.enums.BookingOtpPurpose;
import com.msa.booking.payment.persistence.entity.BookingActionOtpEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Modifying
    @Query("""
            update BookingActionOtpEntity otp
            set otp.otpStatus = :targetStatus
            where otp.bookingId = :bookingId
              and otp.otpPurpose = :otpPurpose
              and otp.otpStatus = :currentStatus
            """)
    int updateStatusByBookingIdAndPurpose(
            @Param("bookingId") Long bookingId,
            @Param("otpPurpose") BookingOtpPurpose otpPurpose,
            @Param("currentStatus") BookingActionOtpStatus currentStatus,
            @Param("targetStatus") BookingActionOtpStatus targetStatus
    );
}
