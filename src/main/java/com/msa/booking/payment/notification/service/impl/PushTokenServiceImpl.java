package com.msa.booking.payment.notification.service.impl;

import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.notification.dto.PushTokenData;
import com.msa.booking.payment.notification.dto.PushTokenDeactivateRequest;
import com.msa.booking.payment.notification.dto.PushTokenRegisterRequest;
import com.msa.booking.payment.notification.service.PushTokenService;
import com.msa.booking.payment.persistence.entity.PushNotificationTokenEntity;
import com.msa.booking.payment.persistence.repository.PushNotificationTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class PushTokenServiceImpl implements PushTokenService {
    private final PushNotificationTokenRepository pushNotificationTokenRepository;

    public PushTokenServiceImpl(PushNotificationTokenRepository pushNotificationTokenRepository) {
        this.pushNotificationTokenRepository = pushNotificationTokenRepository;
    }

    @Override
    @Transactional
    public PushTokenData register(Long userId, PushTokenRegisterRequest request) {
        PushNotificationTokenEntity entity = pushNotificationTokenRepository.findByPushToken(request.pushToken().trim())
                .orElseGet(PushNotificationTokenEntity::new);
        entity.setUserId(userId);
        entity.setUserDeviceId(request.userDeviceId());
        entity.setPlatform(request.platform().trim().toUpperCase(Locale.ROOT));
        entity.setPushProvider(request.pushProvider().trim().toUpperCase(Locale.ROOT));
        entity.setPushToken(request.pushToken().trim());
        entity.setActive(true);
        entity.setLastSeenAt(LocalDateTime.now());
        return toData(pushNotificationTokenRepository.save(entity));
    }

    @Override
    @Transactional
    public void deactivate(Long userId, PushTokenDeactivateRequest request) {
        PushNotificationTokenEntity entity = pushNotificationTokenRepository.findByPushToken(request.pushToken().trim())
                .orElseThrow(() -> new BadRequestException("Push token not found."));
        if (!userId.equals(entity.getUserId())) {
            throw new BadRequestException("Authenticated user cannot deactivate this push token.");
        }
        entity.setActive(false);
        entity.setLastSeenAt(LocalDateTime.now());
        pushNotificationTokenRepository.save(entity);
    }

    private PushTokenData toData(PushNotificationTokenEntity entity) {
        return new PushTokenData(
                entity.getId(),
                entity.getUserId(),
                entity.getUserDeviceId(),
                entity.getPlatform(),
                entity.getPushProvider(),
                entity.getPushToken(),
                entity.isActive(),
                entity.getLastSeenAt()
        );
    }
}
