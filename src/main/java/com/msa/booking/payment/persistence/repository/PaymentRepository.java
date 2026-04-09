package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findByPaymentCode(String paymentCode);

    Optional<PaymentEntity> findByPayableTypeAndPayableId(PayableType payableType, Long payableId);
}
