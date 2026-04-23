package com.msa.booking.payment.booking.support;

import com.msa.booking.payment.config.BookingPaymentSettingsKeys;
import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.persistence.repository.AppSettingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Set;

@Service
public class BookingPolicyServiceImpl implements BookingPolicyService {
    private static final Set<String> SAME_DAY_SERVICE_CATEGORIES = Set.of("plumber", "home appliance", "home appliances");

    private final AppSettingRepository appSettingRepository;

    public BookingPolicyServiceImpl(AppSettingRepository appSettingRepository) {
        this.appSettingRepository = appSettingRepository;
    }

    @Override
    public int labourDirectRequestTimeoutSeconds() {
        return resolveInt(BookingPaymentSettingsKeys.LABOUR_DIRECT_REQUEST_TIMEOUT_SECONDS, 45);
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
    public int reachWarningMinutes() {
        return resolveInt(BookingPaymentSettingsKeys.BOOKING_REACH_WARNING_MINUTES, 10);
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
    public int serviceAutomobileReachTimelineMinutesPerKm() {
        return resolveInt(BookingPaymentSettingsKeys.SERVICE_REACH_TIMELINE_AUTOMOBILE_MINUTES_PER_KM, 5);
    }

    @Override
    public int serviceDefaultReachTimelineMinutes() {
        return resolveInt(BookingPaymentSettingsKeys.SERVICE_REACH_TIMELINE_DEFAULT_MINUTES, 480);
    }

    @Override
    public LocalDateTime resolveReachDeadline(BookingFlowType bookingType, String categoryName, BigDecimal distanceKm, LocalDateTime baseTime) {
        if (baseTime == null) {
            return null;
        }
        if (bookingType == BookingFlowType.LABOUR) {
            return baseTime.plusMinutes(labourReachTimelineMinutes());
        }
        String normalizedCategory = normalizeCategory(categoryName);
        if ("automobile".equals(normalizedCategory)) {
            BigDecimal effectiveDistance = distanceKm == null || distanceKm.signum() <= 0
                    ? BigDecimal.ONE
                    : distanceKm;
            int minutes = effectiveDistance
                    .multiply(BigDecimal.valueOf(serviceAutomobileReachTimelineMinutesPerKm()))
                    .setScale(0, RoundingMode.CEILING)
                    .intValue();
            return baseTime.plusMinutes(Math.max(minutes, serviceAutomobileReachTimelineMinutesPerKm()));
        }
        if (SAME_DAY_SERVICE_CATEGORIES.contains(normalizedCategory)) {
            return baseTime.toLocalDate().atTime(23, 59, 59);
        }
        return baseTime.plusMinutes(serviceDefaultReachTimelineMinutes());
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
    public BigDecimal labourPlatformFeePercent() {
        return resolveAmount(BookingPaymentSettingsKeys.PLATFORM_FEE_LABOUR, new BigDecimal("5.00"));
    }

    @Override
    public BigDecimal labourBookingChargeAmount(BigDecimal labourQuotedAmount) {
        if (labourQuotedAmount == null || labourQuotedAmount.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return labourQuotedAmount
                .multiply(labourPlatformFeePercent())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal servicePlatformFeePercent() {
        return resolveAmount(BookingPaymentSettingsKeys.PLATFORM_FEE_SERVICE, BigDecimal.ZERO);
    }

    @Override
    public BigDecimal servicePlatformFeeAmount(BigDecimal bookingSubtotal) {
        return percentAmount(bookingSubtotal, servicePlatformFeePercent());
    }

    @Override
    public BigDecimal shopPlatformFeePercent() {
        return resolveAmount(BookingPaymentSettingsKeys.PLATFORM_FEE_SHOP, BigDecimal.ZERO);
    }

    @Override
    public BigDecimal shopPlatformFeeAmount(BigDecimal orderSubtotal) {
        return percentAmount(orderSubtotal, shopPlatformFeePercent());
    }

    private String normalizeCategory(String categoryName) {
        return categoryName == null ? "" : categoryName.trim().toLowerCase();
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

    private BigDecimal percentAmount(BigDecimal subtotal, BigDecimal percent) {
        if (subtotal == null || subtotal.signum() <= 0 || percent == null || percent.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return subtotal
                .multiply(percent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
}
