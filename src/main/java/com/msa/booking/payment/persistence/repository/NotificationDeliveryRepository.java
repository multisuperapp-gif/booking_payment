package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.NotificationDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDeliveryEntity, Long> {
}
