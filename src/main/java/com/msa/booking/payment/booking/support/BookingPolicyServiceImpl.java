package com.msa.booking.payment.booking.support;

import com.msa.booking.payment.config.BookingPaymentSettingsKeys;
import com.msa.booking.payment.persistence.repository.AppSettingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BookingPolicyServiceImpl implements BookingPolicyService {
    private final AppSettingRepository appSettingRepository;

    public BookingPolicyServiceImpl(AppSettingRepository appSettingRepository) {
        this.appSettingRepository = appSettingRepository;
    }

    @Override
    public int labourDirectRequestTimeoutSeconds() {
        return resolveInt(BookingPaymentSettingsKeys.LABOUR_DIRECT_REQUEST_TIMEOUT_SECONDS, 30);
    }

    @Override
    public int serviceDirectRequestTimeoutSeconds() {
        return resolveInt(BookingPaymentSettingsKeys.SERVICE_DIRECT_REQUEST_TIMEOUT_SECONDS, 30);
    }

    @Override
    public int acceptedPaymentTimeoutSeconds() {
        return resolveInt(BookingPaymentSettingsKeys.BOOKING_ACCEPTED_PAYMENT_TIMEOUT_SECONDS, 300);
    }

    @Override
    public int noShowAutoCancelMinutes() {
        return resolveInt(BookingPaymentSettingsKeys.BOOKING_NO_SHOW_AUTO_CANCEL_MINUTES, 120);
    }

    @Override
    public int labourReachTimelineMinutes() {
        return resolveInt(BookingPaymentSettingsKeys.LABOUR_REACH_TIMELINE_MINUTES, 45);
    }

    @Override
    public int serviceAutomobileReachTimelineMinutes() {
        return resolveInt(BookingPaymentSettingsKeys.SERVICE_REACH_TIMELINE_AUTOMOBILE_MINUTES, 45);
    }

    @Override
    public int serviceDefaultReachTimelineMinutes() {
        return resolveInt(BookingPaymentSettingsKeys.SERVICE_REACH_TIMELINE_DEFAULT_MINUTES, 480);
    }

    @Override
    public int labourNoShowSuspendThreshold() {
        return resolveInt(BookingPaymentSettingsKeys.LABOUR_MONTHLY_NO_SHOW_SUSPEND_THRESHOLD, 2);
    }

    @Override
    public int serviceNoShowSuspendThreshold() {
        return resolveInt(BookingPaymentSettingsKeys.SERVICE_MONTHLY_NO_SHOW_SUSPEND_THRESHOLD, 2);
    }

    @Override
    public BigDecimal postStartCancellationPenaltyAmount() {
        return resolveAmount(BookingPaymentSettingsKeys.USER_POST_START_CANCELLATION_PENALTY_AMOUNT, new BigDecimal("100.00"));
    }

    @Override
    public BigDecimal labourPlatformFee() {
        return resolveAmount(BookingPaymentSettingsKeys.PLATFORM_FEE_LABOUR, BigDecimal.ZERO);
    }

    @Override
    public BigDecimal servicePlatformFee() {
        return resolveAmount(BookingPaymentSettingsKeys.PLATFORM_FEE_SERVICE, BigDecimal.ZERO);
    }

    @Override
    public BigDecimal shopPlatformFee() {
        return resolveAmount(BookingPaymentSettingsKeys.PLATFORM_FEE_SHOP, BigDecimal.ZERO);
    }

    private int resolveInt(String key, int fallback) {
        return appSettingRepository.findBySettingKey(key)
                .map(setting -> parseInt(setting.getSettingValue(), fallback))
                .orElse(fallback);
    }

    private BigDecimal resolveAmount(String key, BigDecimal fallback) {
        return appSettingRepository.findBySettingKey(key)
                .map(setting -> parseAmount(setting.getSettingValue(), fallback))
                .orElse(fallback);
    }

    private int parseInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private BigDecimal parseAmount(String value, BigDecimal fallback) {
        try {
            BigDecimal parsed = new BigDecimal(value == null ? "" : value.trim());
            return parsed.signum() >= 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
