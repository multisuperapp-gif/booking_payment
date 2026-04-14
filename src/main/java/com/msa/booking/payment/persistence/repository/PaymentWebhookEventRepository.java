package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.PaymentWebhookEventEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEventEntity, Long> {
    Optional<PaymentWebhookEventEntity> findBySourceSystemAndWebhookKey(String sourceSystem, String webhookKey);
}
