package com.msa.booking.payment.notification.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationServiceImpl implements NotificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationServiceImpl.class);
    private static final String INCOMING_BOOKING_CHANNEL_ID = "incoming_bookings";
    private static final String INCOMING_BOOKING_SOUND_NAME = "incoming_booking_alert";
    private static final String INCOMING_BOOKING_SOUND_FILE = "incoming_booking_alert.wav";
    private static final String BOOKING_UPDATES_CHANNEL_ID = "booking_updates";
    private static final String BOOKING_UPDATES_SOUND_NAME = "skins_theme_short";
    private static final String BOOKING_UPDATES_SOUND_FILE = "skins_theme_short.mp3";
    private static final String USER_APP_CONTEXT = "USER_APP";
    private static final String PROVIDER_APP_CONTEXT = "PROVIDER_APP";

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

        String targetAppContext = targetAppContext(type, payload);
        List<PushNotificationTokenEntity> pushTokens =
                pushNotificationTokenRepository.findByUserIdAndAppContextAndActiveTrue(userId, targetAppContext);
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
                Map<String, String> data = buildDataPayload(saved.getId(), type, title, body, payload);
                boolean incomingBookingRequest = isIncomingBookingRequest(type);
                boolean androidToken = "ANDROID".equalsIgnoreCase(pushToken.getPlatform());
                Message.Builder messageBuilder = Message.builder()
                        .setToken(pushToken.getPushToken())
                        .putAllData(data);
                if (!incomingBookingRequest || !androidToken) {
                    messageBuilder.setNotification(Notification.builder().setTitle(title).setBody(body).build());
                }
                if (incomingBookingRequest) {
                    AndroidConfig.Builder androidConfigBuilder = AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setTtl(300_000L);
                    if (!androidToken) {
                        androidConfigBuilder.setNotification(AndroidNotification.builder()
                                .setChannelId(INCOMING_BOOKING_CHANNEL_ID)
                                .setSound(INCOMING_BOOKING_SOUND_NAME)
                                .setPriority(AndroidNotification.Priority.MAX)
                                .setVisibility(AndroidNotification.Visibility.PUBLIC)
                                .setDefaultVibrateTimings(true)
                                .build());
                    }
                    messageBuilder
                            .setAndroidConfig(androidConfigBuilder.build())
                            .setApnsConfig(ApnsConfig.builder()
                                    .putHeader("apns-priority", "10")
                                    .setAps(Aps.builder()
                                            .setSound(INCOMING_BOOKING_SOUND_FILE)
                                            .setCategory("INCOMING_BOOKING")
                                    .build())
                                    .build());
                } else if (hasBookingUpdateSound(type)) {
                    messageBuilder
                            .setAndroidConfig(AndroidConfig.builder()
                                    .setPriority(AndroidConfig.Priority.HIGH)
                                    .setNotification(AndroidNotification.builder()
                                            .setChannelId(BOOKING_UPDATES_CHANNEL_ID)
                                            .setSound(BOOKING_UPDATES_SOUND_NAME)
                                            .build())
                                    .build())
                            .setApnsConfig(ApnsConfig.builder()
                                    .putHeader("apns-priority", "10")
                                    .setAps(Aps.builder()
                                            .setSound(BOOKING_UPDATES_SOUND_FILE)
                                            .build())
                                    .build());
                }
                String messageId = FirebaseMessaging.getInstance().send(
                        messageBuilder.build()
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

    private Map<String, String> buildDataPayload(Long notificationId, String type, String title, String body, Map<String, Object> payload) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", type == null ? "" : type);
        data.put("notificationId", String.valueOf(notificationId));
        data.put("title", title == null ? "" : title);
        data.put("body", body == null ? "" : body);
        if (isIncomingBookingRequest(type)) {
            data.put("priority", "high");
            data.put("notificationChannelId", INCOMING_BOOKING_CHANNEL_ID);
            data.put("sound", INCOMING_BOOKING_SOUND_NAME);
            data.put("requiresProviderAction", "true");
            data.put("fullScreenAlert", "true");
        } else if (hasBookingUpdateSound(type)) {
            data.put("notificationChannelId", BOOKING_UPDATES_CHANNEL_ID);
            data.put("sound", BOOKING_UPDATES_SOUND_NAME);
        }
        if (payload != null) {
            payload.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) {
                    return;
                }
                data.put(key, String.valueOf(value));
            });
        }
        return data;
    }

    private boolean isIncomingBookingRequest(String type) {
        return "BOOKING_REQUEST_NEW".equalsIgnoreCase(type);
    }

    private boolean hasBookingUpdateSound(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase();
        return "BOOKING_ACCEPTED".equals(normalized)
                || "BOOKING_PAYMENT_SUCCESS".equals(normalized)
                || "BOOKING_PROVIDER_ARRIVED".equals(normalized)
                || "BOOKING_CANCELLED".equals(normalized)
                || "BOOKING_COMPLETED".equals(normalized)
                || "BOOKING_PAYMENT_SUCCESS_PROVIDER".equals(normalized)
                || "BOOKING_CANCELLED_PROVIDER".equals(normalized)
                || "BOOKING_WORK_STARTED_PROVIDER".equals(normalized);
    }

    private String targetAppContext(String type, Map<String, Object> payload) {
        Object explicitContext = payload == null ? null : payload.get("appContext");
        if (explicitContext != null && !String.valueOf(explicitContext).isBlank()) {
            String normalizedContext = String.valueOf(explicitContext).trim().toUpperCase();
            if (USER_APP_CONTEXT.equals(normalizedContext) || PROVIDER_APP_CONTEXT.equals(normalizedContext)) {
                return normalizedContext;
            }
        }
        String normalized = type == null ? "" : type.trim().toUpperCase();
        if (isIncomingBookingRequest(normalized)
                || normalized.endsWith("_PROVIDER")
                || "BOOKING_ASSIGNED".equals(normalized)
                || "BOOKING_MUTUAL_CANCEL_OTP".equals(normalized)
                || "SHOP_ORDER_RECEIVED".equals(normalized)) {
            return PROVIDER_APP_CONTEXT;
        }
        return USER_APP_CONTEXT;
    }
}
