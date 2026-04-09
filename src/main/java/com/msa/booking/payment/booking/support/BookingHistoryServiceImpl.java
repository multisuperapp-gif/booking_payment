package com.msa.booking.payment.booking.support;

import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.BookingStatusHistoryEntity;
import com.msa.booking.payment.persistence.entity.OrderEntity;
import com.msa.booking.payment.persistence.entity.OrderStatusHistoryEntity;
import com.msa.booking.payment.persistence.repository.BookingStatusHistoryRepository;
import com.msa.booking.payment.persistence.repository.OrderStatusHistoryRepository;
import org.springframework.stereotype.Service;

@Service
public class BookingHistoryServiceImpl implements BookingHistoryService {
    private final BookingStatusHistoryRepository bookingStatusHistoryRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    public BookingHistoryServiceImpl(
            BookingStatusHistoryRepository bookingStatusHistoryRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository
    ) {
        this.bookingStatusHistoryRepository = bookingStatusHistoryRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
    }

    @Override
    public void recordBookingStatus(BookingEntity booking, String oldStatus, String newStatus, Long changedByUserId, String reason) {
        BookingStatusHistoryEntity entity = new BookingStatusHistoryEntity();
        entity.setBookingId(booking.getId());
        entity.setOldStatus(oldStatus);
        entity.setNewStatus(newStatus);
        entity.setChangedByUserId(changedByUserId);
        entity.setReason(reason);
        bookingStatusHistoryRepository.save(entity);
    }

    @Override
    public void recordOrderStatus(OrderEntity order, String oldStatus, String newStatus, Long changedByUserId, String reason) {
        OrderStatusHistoryEntity entity = new OrderStatusHistoryEntity();
        entity.setOrderId(order.getId());
        entity.setOldStatus(oldStatus);
        entity.setNewStatus(newStatus);
        entity.setChangedByUserId(changedByUserId);
        entity.setReason(reason);
        orderStatusHistoryRepository.save(entity);
    }
}
