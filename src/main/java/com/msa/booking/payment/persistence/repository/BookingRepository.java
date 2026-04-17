package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<BookingEntity, Long> {
    Optional<BookingEntity> findByBookingRequestId(Long bookingRequestId);

    List<BookingEntity> findTop100ByBookingStatusAndPaymentStatusInAndCreatedAtBefore(
            BookingLifecycleStatus bookingStatus,
            Collection<PayablePaymentStatus> paymentStatuses,
            LocalDateTime createdAt
    );

    List<BookingEntity> findTop100ByBookingStatusAndPaymentStatusAndScheduledStartAtBefore(
            BookingLifecycleStatus bookingStatus,
            PayablePaymentStatus paymentStatus,
            LocalDateTime scheduledStartAt
    );

    List<BookingEntity> findTop500ByBookingStatusAndPaymentStatusAndScheduledStartAtAfterOrderByScheduledStartAtAsc(
            BookingLifecycleStatus bookingStatus,
            PayablePaymentStatus paymentStatus,
            LocalDateTime scheduledStartAt
    );
}
