package com.msa.booking.payment.notification.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.persistence.entity.NotificationDeliveryEntity;
import com.msa.booking.payment.persistence.entity.NotificationEntity;
import com.msa.booking.payment.persistence.entity.PushNotificationTokenEntity;
import com.msa.booking.payment.persistence.repository.NotificationDeliveryRepository;
import com.msa.booking.payment.persistence.repository.NotificationRepository;
import com.msa.booking.payment.persistence.repository.PushNotificationTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class NotificationServiceImpl implements NotificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository notificationDeliveryRepository;
    private final PushNotificationTokenRepository pushNotificationTokenRepository;
    private final ObjectMapper objectMapper;

    public NotificationServiceImpl(
            NotificationRepository notificationRepository,
            NotificationDeliveryRepository notificationDeliveryRepository,
            PushNotificationTokenRepository pushNotificationTokenRepository,
            ObjectMapper objectMapper
    ) {
        this.notificationRepository = notificationRepository;
        this.notificationDeliveryRepository = notificationDeliveryRepository;
        this.pushNotificationTokenRepository = pushNotificationTokenRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void notifyUser(Long userId, String type, String title, String body, Map<String, Object> payload) {
        NotificationEntity entity = new NotificationEntity();
        entity.setUserId(userId);
        entity.setChannel("PUSH");
        entity.setNotificationType(type);
        entity.setTitle(title);
        entity.setBody(body);
        entity.setPayloadJson(toJson(payload));
        entity.setStatus("PENDING");
        NotificationEntity saved = notificationRepository.save(entity);

        List<PushNotificationTokenEntity> pushTokens = pushNotificationTokenRepository.findByUserIdAndActiveTrue(userId);
        if (pushTokens.isEmpty()) {
            saved.setStatus("FAILED");
            notificationRepository.save(saved);
            return;
        }

        boolean anySent = false;
        for (PushNotificationTokenEntity pushToken : pushTokens) {
            NotificationDeliveryEntity delivery = new NotificationDeliveryEntity();
            delivery.setNotificationId(saved.getId());
            delivery.setPushTokenId(pushToken.getId());
            delivery.setChannel("PUSH");
            delivery.setDeliveryStatus("PENDING");
            try {
                String messageId = FirebaseMessaging.getInstance().send(
                        Message.builder()
                                .setToken(pushToken.getPushToken())
                                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                                .putData("type", type)
                                .putData("notificationId", String.valueOf(saved.getId()))
                                .build()
                );
                delivery.setDeliveryStatus("SENT");
                delivery.setProviderMessageId(messageId);
                delivery.setDeliveredAt(LocalDateTime.now());
                anySent = true;
            } catch (Exception exception) {
                delivery.setDeliveryStatus("FAILED");
                delivery.setFailureReason(exception.getMessage());
                LOGGER.warn("Failed to send push notification. userId={}, tokenId={}, type={}", userId, pushToken.getId(), type);
            }
            notificationDeliveryRepository.save(delivery);
        }

        saved.setStatus(anySent ? "SENT" : "FAILED");
        if (anySent) {
            saved.setSentAt(LocalDateTime.now());
        }
        notificationRepository.save(saved);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return payload == null ? null : objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
