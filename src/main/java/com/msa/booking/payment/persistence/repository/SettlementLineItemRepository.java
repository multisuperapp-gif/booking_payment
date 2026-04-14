package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.SettlementLineItemEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementLineItemRepository extends JpaRepository<SettlementLineItemEntity, Long> {
    boolean existsBySourceTypeAndSourceIdAndLineType(String sourceType, Long sourceId, String lineType);

    Optional<SettlementLineItemEntity> findTopBySourceTypeAndSourceIdAndLineTypeOrderByIdAsc(
            String sourceType,
            Long sourceId,
            String lineType
    );
}
