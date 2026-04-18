package com.msa.booking.payment.booking.service;

import com.msa.booking.payment.booking.dto.ProviderPendingBookingRequestData;
import com.msa.booking.payment.booking.dto.ProviderActiveBookingData;
import com.msa.booking.payment.booking.dto.UserBookingRequestStatusData;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import java.util.List;

public interface BookingRequestQueryService {
    List<ProviderPendingBookingRequestData> pendingForProvider(Long actingUserId, ProviderEntityType providerEntityType);

    ProviderActiveBookingData latestActiveForProvider(Long actingUserId, ProviderEntityType providerEntityType);

    UserBookingRequestStatusData statusForUser(Long actingUserId, Long requestId);

    List<UserBookingRequestStatusData> activeForUser(Long actingUserId);

    UserBookingRequestStatusData latestActiveForUser(Long actingUserId);
}
