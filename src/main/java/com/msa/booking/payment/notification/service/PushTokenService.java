package com.msa.booking.payment.notification.service;

import com.msa.booking.payment.notification.dto.PushTokenData;
import com.msa.booking.payment.notification.dto.PushTokenDeactivateRequest;
import com.msa.booking.payment.notification.dto.PushTokenRegisterRequest;

public interface PushTokenService {
    PushTokenData register(Long userId, PushTokenRegisterRequest request);

    void deactivate(Long userId, PushTokenDeactivateRequest request);
}
