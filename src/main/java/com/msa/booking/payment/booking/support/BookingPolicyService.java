package com.msa.booking.payment.booking.support;

import java.math.BigDecimal;

public interface BookingPolicyService {
    int labourDirectRequestTimeoutSeconds();

    int serviceDirectRequestTimeoutSeconds();

    int acceptedPaymentTimeoutSeconds();

    int noShowAutoCancelMinutes();

    int reachWarningMinutes();

    int labourReachTimelineMinutes();

    int serviceAutomobileReachTimelineMinutes();

    int serviceDefaultReachTimelineMinutes();

    int labourNoShowSuspendThreshold();

    int serviceNoShowSuspendThreshold();

    BigDecimal postStartCancellationPenaltyAmount();

    BigDecimal labourPlatformFeePercent();

    BigDecimal labourBookingChargeAmount(BigDecimal labourQuotedAmount);

    BigDecimal servicePlatformFee();

    BigDecimal shopPlatformFee();
}
