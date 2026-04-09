package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefundRepository extends JpaRepository<RefundEntity, Long> {
    Optional<RefundEntity> findByRefundCode(String refundCode);
}
