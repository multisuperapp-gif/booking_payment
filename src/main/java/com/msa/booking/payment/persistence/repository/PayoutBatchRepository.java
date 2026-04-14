package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.PayoutBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatchEntity, Long> {
}
