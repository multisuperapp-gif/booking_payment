package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.PayoutBatchItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutBatchItemRepository extends JpaRepository<PayoutBatchItemEntity, Long> {
    List<PayoutBatchItemEntity> findAllByPayoutBatchIdOrderByIdAsc(Long payoutBatchId);
}
