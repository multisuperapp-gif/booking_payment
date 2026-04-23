package com.msa.booking.payment.booking.support;

import com.msa.booking.payment.domain.enums.BookingFlowType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface BookingPolicyService {
    int labourDirectRequestTimeoutSeconds();

    int serviceDirectRequestTimeoutSeconds();

    int acceptedPaymentTimeoutSeconds();

    int noShowAutoCancelMinutes();

    int reachWarningMinutes();

    int labourReachTimelineMinutes();

    int serviceAutomobileReachTimelineMinutes();

    int serviceAutomobileReachTimelineMinutesPerKm();

    int serviceDefaultReachTimelineMinutes();

    LocalDateTime resolveReachDeadline(BookingFlowType bookingType, String categoryName, BigDecimal distanceKm, LocalDateTime baseTime);

    LocalDateTime resolveServiceStartWorkOtpExpiry(String categoryName, BigDecimal distanceKm, LocalDateTime baseTime);

    int labourNoShowSuspendThreshold();

    int serviceNoShowSuspendThreshold();

    BigDecimal postStartCancellationPenaltyAmount();

    BigDecimal labourPlatformFeePercent();

    BigDecimal labourBookingChargeAmount(BigDecimal labourQuotedAmount);

    BigDecimal servicePlatformFeePercent();

    BigDecimal servicePlatformFeeAmount(BigDecimal bookingSubtotal);

    BigDecimal shopPlatformFeePercent();

    BigDecimal shopPlatformFeeAmount(BigDecimal orderSubtotal);
}
