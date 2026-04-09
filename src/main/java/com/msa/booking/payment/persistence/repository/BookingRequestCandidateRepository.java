package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.BookingRequestCandidateStatus;
import com.msa.booking.payment.persistence.entity.BookingRequestCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingRequestCandidateRepository extends JpaRepository<BookingRequestCandidateEntity, Long> {
    List<BookingRequestCandidateEntity> findByRequestId(Long requestId);

    List<BookingRequestCandidateEntity> findByRequestIdAndCandidateStatus(Long requestId, BookingRequestCandidateStatus candidateStatus);

    java.util.Optional<BookingRequestCandidateEntity> findByIdAndRequestId(Long id, Long requestId);
}
