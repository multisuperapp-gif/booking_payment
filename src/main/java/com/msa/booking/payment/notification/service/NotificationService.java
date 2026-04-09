package com.msa.booking.payment.notification.service;

import java.util.Map;

public interface NotificationService {
    void notifyUser(Long userId, String type, String title, String body, Map<String, Object> payload);
}
