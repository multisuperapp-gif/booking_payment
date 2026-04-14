package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findByPaymentCode(String paymentCode);

    Optional<PaymentEntity> findByPayableTypeAndPayableId(PayableType payableType, Long payableId);
}
