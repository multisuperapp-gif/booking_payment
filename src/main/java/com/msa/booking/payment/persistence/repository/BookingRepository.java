package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.BookingEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<BookingEntity, Long> {
    Optional<BookingEntity> findByBookingRequestId(Long bookingRequestId);
}
