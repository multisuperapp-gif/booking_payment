package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.BookingLineItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingLineItemRepository extends JpaRepository<BookingLineItemEntity, Long> {
    List<BookingLineItemEntity> findByBookingId(Long bookingId);
}
