package com.msa.booking.payment.booking.support;

import java.math.BigDecimal;

public interface BookingPolicyService {
    int labourDirectRequestTimeoutSeconds();

    int serviceDirectRequestTimeoutSeconds();

    int labourReachTimelineMinutes();

    int serviceAutomobileReachTimelineMinutes();

    int serviceDefaultReachTimelineMinutes();

    int labourNoShowSuspendThreshold();

    int serviceNoShowSuspendThreshold();

    BigDecimal postStartCancellationPenaltyAmount();

    BigDecimal labourPlatformFee();

    BigDecimal servicePlatformFee();

    BigDecimal shopPlatformFee();
}
