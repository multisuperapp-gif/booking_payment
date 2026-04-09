package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.BookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<BookingEntity, Long> {
    Optional<BookingEntity> findByBookingCode(String bookingCode);
}
