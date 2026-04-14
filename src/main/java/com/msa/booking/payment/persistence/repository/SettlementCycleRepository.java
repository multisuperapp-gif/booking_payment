package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.SettlementCycleEntity;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementCycleRepository extends JpaRepository<SettlementCycleEntity, Long> {
    Optional<SettlementCycleEntity> findByCycleTypeAndPeriodStartAndPeriodEnd(
            String cycleType,
            LocalDate periodStart,
            LocalDate periodEnd
    );
}
