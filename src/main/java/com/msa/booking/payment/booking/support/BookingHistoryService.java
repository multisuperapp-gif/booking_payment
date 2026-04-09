package com.msa.booking.payment.booking.support;

import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.OrderEntity;

public interface BookingHistoryService {
    void recordBookingStatus(BookingEntity booking, String oldStatus, String newStatus, Long changedByUserId, String reason);

    void recordOrderStatus(OrderEntity order, String oldStatus, String newStatus, Long changedByUserId, String reason);
}
