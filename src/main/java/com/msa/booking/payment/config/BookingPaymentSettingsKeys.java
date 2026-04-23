package com.msa.booking.payment.config;

public final class BookingPaymentSettingsKeys {
    private BookingPaymentSettingsKeys() {
    }

    public static final String LABOUR_DIRECT_REQUEST_TIMEOUT_SECONDS = "labour.direct.request.timeout.seconds";
    public static final String SERVICE_DIRECT_REQUEST_TIMEOUT_SECONDS = "service.direct.request.timeout.seconds";
    public static final String BOOKING_ACCEPTED_PAYMENT_TIMEOUT_SECONDS = "booking.accepted.payment.timeout.seconds";
    public static final String BOOKING_NO_SHOW_AUTO_CANCEL_MINUTES = "booking.no.show.auto.cancel.minutes";
    public static final String BOOKING_REACH_WARNING_MINUTES = "booking.reach.warning.minutes";
    public static final String LABOUR_REACH_TIMELINE_MINUTES = "labour.reach.timeline.minutes";
    public static final String SERVICE_REACH_TIMELINE_AUTOMOBILE_MINUTES = "service.reach.timeline.automobile.minutes";
    public static final String SERVICE_REACH_TIMELINE_AUTOMOBILE_MINUTES_PER_KM = "service.reach.timeline.automobile.minutes.per.km";
    public static final String SERVICE_REACH_TIMELINE_DEFAULT_MINUTES = "service.reach.timeline.default.minutes";
    public static final String LABOUR_MONTHLY_NO_SHOW_SUSPEND_THRESHOLD = "labour.monthly.no.show.suspend.threshold";
    public static final String SERVICE_MONTHLY_NO_SHOW_SUSPEND_THRESHOLD = "service.monthly.no.show.suspend.threshold";
    public static final String USER_POST_START_CANCELLATION_PENALTY_AMOUNT = "user.post.start.cancellation.penalty.amount";
    public static final String PLATFORM_FEE_LABOUR = "platform.fee.labour";
    public static final String PLATFORM_FEE_SERVICE = "platform.fee.service";
    public static final String PLATFORM_FEE_SHOP = "platform.fee.shop";
}
