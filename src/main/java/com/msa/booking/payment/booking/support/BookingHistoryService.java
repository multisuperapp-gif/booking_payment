package com.msa.booking.payment.booking.support;

import com.msa.booking.payment.persistence.entity.BookingEntity;

public interface BookingHistoryService {
    void recordBookingStatus(BookingEntity booking, String oldStatus, String newStatus, Long changedByUserId, String reason);
}
