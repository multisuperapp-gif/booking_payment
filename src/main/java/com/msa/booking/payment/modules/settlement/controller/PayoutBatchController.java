package com.msa.booking.payment.modules.settlement.controller;

import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.CompletePayoutBatchRequest;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.CreatePayoutBatchRequest;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.FailPayoutBatchRequest;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.PayoutBatchData;
import com.msa.booking.payment.modules.settlement.service.PayoutBatchService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/payout-batches")
public class PayoutBatchController {
    private final PayoutBatchService payoutBatchService;

    public PayoutBatchController(PayoutBatchService payoutBatchService) {
        this.payoutBatchService = payoutBatchService;
    }

    @PostMapping
    public ApiResponse<PayoutBatchData> createBatch(@RequestBody(required = false) CreatePayoutBatchRequest request) {
        return ApiResponse.ok(payoutBatchService.createBatch(request));
    }

    @GetMapping("/{batchId}")
    public ApiResponse<PayoutBatchData> getBatch(@PathVariable Long batchId) {
        return ApiResponse.ok(payoutBatchService.getBatch(batchId));
    }

    @PostMapping("/{batchId}/complete")
    public ApiResponse<PayoutBatchData> completeBatch(
            @PathVariable Long batchId,
            @RequestBody(required = false) CompletePayoutBatchRequest request
    ) {
        return ApiResponse.ok(payoutBatchService.completeBatch(batchId, request));
    }

    @PostMapping("/{batchId}/fail")
    public ApiResponse<PayoutBatchData> failBatch(
            @PathVariable Long batchId,
            @RequestBody(required = false) FailPayoutBatchRequest request
    ) {
        return ApiResponse.ok(payoutBatchService.failBatch(batchId, request));
    }
}
