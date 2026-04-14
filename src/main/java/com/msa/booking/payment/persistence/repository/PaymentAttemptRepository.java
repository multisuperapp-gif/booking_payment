package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, Long> {
    Optional<PaymentAttemptEntity> findTopByPaymentIdOrderByIdDesc(Long paymentId);

    Optional<PaymentAttemptEntity> findTopByPaymentIdAndGatewayNameOrderByIdDesc(Long paymentId, String gatewayName);

    Optional<PaymentAttemptEntity> findTopByGatewayOrderIdOrderByIdDesc(String gatewayOrderId);

    Optional<PaymentAttemptEntity> findFirstByGatewayOrderIdOrderByAttemptedAtDesc(String gatewayOrderId);
}
