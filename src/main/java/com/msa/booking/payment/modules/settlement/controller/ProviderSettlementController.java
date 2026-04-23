package com.msa.booking.payment.modules.settlement.controller;

import static com.msa.booking.payment.modules.settlement.dto.ProviderSettlementDtos.ProviderSettlementDashboardData;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.modules.settlement.service.ProviderSettlementQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/provider/settlements")
public class ProviderSettlementController {
    private final ProviderSettlementQueryService providerSettlementQueryService;

    public ProviderSettlementController(ProviderSettlementQueryService providerSettlementQueryService) {
        this.providerSettlementQueryService = providerSettlementQueryService;
    }

    @GetMapping("/weekly")
    public ApiResponse<ProviderSettlementDashboardData> weeklyDashboard(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam ProviderEntityType providerEntityType
    ) {
        return ApiResponse.ok(providerSettlementQueryService.weeklyDashboard(userId, providerEntityType));
    }
}
