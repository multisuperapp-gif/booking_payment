package com.msa.booking.payment.persistence.repository;

import java.math.BigDecimal;

public interface ServiceCandidateProjection {
    Long getProviderEntityId();

    BigDecimal getQuotedPriceAmount();

    BigDecimal getDistanceKm();
}
