package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.SuspensionStatus;
import com.msa.booking.payment.persistence.entity.SuspensionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuspensionRepository extends JpaRepository<SuspensionEntity, Long> {
    List<SuspensionEntity> findByUserIdAndStatusOrderByStartAtDesc(Long userId, SuspensionStatus status);
}
