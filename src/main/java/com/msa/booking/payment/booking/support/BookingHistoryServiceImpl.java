package com.msa.booking.payment.booking.support;

import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.BookingStatusHistoryEntity;
import com.msa.booking.payment.persistence.repository.BookingStatusHistoryRepository;
import org.springframework.stereotype.Service;

@Service
public class BookingHistoryServiceImpl implements BookingHistoryService {
    private final BookingStatusHistoryRepository bookingStatusHistoryRepository;

    public BookingHistoryServiceImpl(BookingStatusHistoryRepository bookingStatusHistoryRepository) {
        this.bookingStatusHistoryRepository = bookingStatusHistoryRepository;
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
}
