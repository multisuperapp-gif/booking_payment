package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.BookingRequestStatus;
import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRequestRepository extends JpaRepository<BookingRequestEntity, Long> {
    Optional<BookingRequestEntity> findByRequestCode(String requestCode);

    List<BookingRequestEntity> findByRequestStatusAndExpiresAtBefore(BookingRequestStatus requestStatus, LocalDateTime expiresAt);

    Optional<BookingRequestEntity> findByIdAndRequestStatus(Long id, BookingRequestStatus requestStatus);

    List<BookingRequestEntity> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    List<BookingRequestEntity> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
}
