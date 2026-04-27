package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.BookingRequestCandidateStatus;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.persistence.entity.BookingRequestCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingRequestCandidateRepository extends JpaRepository<BookingRequestCandidateEntity, Long> {
    List<BookingRequestCandidateEntity> findByRequestId(Long requestId);

    List<BookingRequestCandidateEntity> findByRequestIdAndCandidateStatus(Long requestId, BookingRequestCandidateStatus candidateStatus);

    List<BookingRequestCandidateEntity> findByProviderEntityTypeAndProviderEntityIdAndCandidateStatus(
            com.msa.booking.payment.domain.enums.ProviderEntityType providerEntityType,
            Long providerEntityId,
            BookingRequestCandidateStatus candidateStatus
    );

    java.util.Optional<BookingRequestCandidateEntity> findByIdAndRequestId(Long id, Long requestId);

    Optional<BookingRequestCandidateEntity> findTopByRequestIdAndProviderEntityTypeAndProviderEntityIdAndCandidateStatusOrderByIdDesc(
            Long requestId,
            ProviderEntityType providerEntityType,
            Long providerEntityId,
            BookingRequestCandidateStatus candidateStatus
    );
}
