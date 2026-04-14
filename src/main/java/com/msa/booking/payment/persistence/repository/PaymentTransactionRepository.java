package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.PaymentTransactionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, Long> {
    Optional<PaymentTransactionEntity> findTopByPaymentIdOrderByIdDesc(Long paymentId);

    boolean existsByGatewayTransactionId(String gatewayTransactionId);

    Optional<PaymentTransactionEntity> findByGatewayTransactionId(String gatewayTransactionId);
}
