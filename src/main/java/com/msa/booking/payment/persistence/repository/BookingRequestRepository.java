package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.BookingRequestStatus;
import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRequestRepository extends JpaRepository<BookingRequestEntity, Long> {
    Optional<BookingRequestEntity> findByRequestCode(String requestCode);

    List<BookingRequestEntity> findByRequestStatusAndExpiresAtBefore(BookingRequestStatus requestStatus, LocalDateTime expiresAt);

    Optional<BookingRequestEntity> findByIdAndRequestStatus(Long id, BookingRequestStatus requestStatus);

    List<BookingRequestEntity> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    List<BookingRequestEntity> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    @Query(value = """
            SELECT COALESCE(pc.name, 'Service')
            FROM booking_requests br
            LEFT JOIN provider_categories pc ON pc.id = br.category_id
            WHERE br.id = :requestId
            """, nativeQuery = true)
    Optional<String> findCategoryNameByRequestId(@Param("requestId") Long requestId);
}
