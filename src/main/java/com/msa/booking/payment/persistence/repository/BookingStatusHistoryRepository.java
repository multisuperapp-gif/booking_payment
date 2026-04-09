package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.BookingStatusHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingStatusHistoryRepository extends JpaRepository<BookingStatusHistoryEntity, Long> {
    List<BookingStatusHistoryEntity> findByBookingIdOrderByChangedAtAsc(Long bookingId);
}
