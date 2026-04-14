package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.SettlementEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<SettlementEntity, Long> {
    Optional<SettlementEntity> findBySettlementCycleIdAndBeneficiaryTypeAndBeneficiaryId(
            Long settlementCycleId,
            String beneficiaryType,
            Long beneficiaryId
    );

    List<SettlementEntity> findAllByStatusOrderByIdAsc(String status);
}
