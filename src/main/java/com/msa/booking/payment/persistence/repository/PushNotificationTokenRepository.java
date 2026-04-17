package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.PushNotificationTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PushNotificationTokenRepository extends JpaRepository<PushNotificationTokenEntity, Long> {
    List<PushNotificationTokenEntity> findByUserIdAndActiveTrue(Long userId);

    java.util.Optional<PushNotificationTokenEntity> findByPushToken(String pushToken);
}
