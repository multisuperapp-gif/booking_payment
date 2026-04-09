package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.PenaltyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PenaltyRepository extends JpaRepository<PenaltyEntity, Long> {
    List<PenaltyEntity> findByPenalizedUserIdOrderByAppliedAtDesc(Long penalizedUserId);
}
