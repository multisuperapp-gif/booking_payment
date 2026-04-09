package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.PaymentTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, Long> {
    Optional<PaymentTransactionEntity> findByGatewayTransactionId(String gatewayTransactionId);
}
