package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, Long> {
    List<PaymentAttemptEntity> findByPaymentIdOrderByAttemptedAtDesc(Long paymentId);

    Optional<PaymentAttemptEntity> findFirstByPaymentIdOrderByAttemptedAtDesc(Long paymentId);

    Optional<PaymentAttemptEntity> findFirstByGatewayOrderIdOrderByAttemptedAtDesc(String gatewayOrderId);
}
