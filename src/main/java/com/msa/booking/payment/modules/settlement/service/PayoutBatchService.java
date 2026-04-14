package com.msa.booking.payment.modules.settlement.service;

import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.common.exception.ResourceNotFoundException;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.CompletePayoutBatchRequest;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.CreatePayoutBatchRequest;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.FailPayoutBatchRequest;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.PayoutBatchData;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.PayoutBatchItemData;
import com.msa.booking.payment.persistence.entity.PayoutBatchEntity;
import com.msa.booking.payment.persistence.entity.PayoutBatchItemEntity;
import com.msa.booking.payment.persistence.entity.SettlementEntity;
import com.msa.booking.payment.persistence.repository.PayoutBatchItemRepository;
import com.msa.booking.payment.persistence.repository.PayoutBatchRepository;
import com.msa.booking.payment.persistence.repository.SettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayoutBatchService {
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_PAID = "PAID";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_INITIATED = "INITIATED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final PayoutBatchRepository payoutBatchRepository;
    private final PayoutBatchItemRepository payoutBatchItemRepository;
    private final SettlementRepository settlementRepository;

    public PayoutBatchService(
            PayoutBatchRepository payoutBatchRepository,
            PayoutBatchItemRepository payoutBatchItemRepository,
            SettlementRepository settlementRepository
    ) {
        this.payoutBatchRepository = payoutBatchRepository;
        this.payoutBatchItemRepository = payoutBatchItemRepository;
        this.settlementRepository = settlementRepository;
    }

    @Transactional
    public PayoutBatchData createBatch(CreatePayoutBatchRequest request) {
        String beneficiaryType = request == null ? null : normalizeBlank(request.beneficiaryType());
        int limit = request == null || request.limit() == null || request.limit() <= 0 ? 100 : request.limit();
        List<Long> requestedSettlementIds = request == null ? null : request.settlementIds();

        List<SettlementEntity> selectedSettlements = settlementRepository.findAllByStatusOrderByIdAsc(STATUS_PENDING).stream()
                .filter(settlement -> beneficiaryType == null
                        || beneficiaryType.equalsIgnoreCase(settlement.getBeneficiaryType()))
                .filter(settlement -> settlement.getNetAmount() != null && settlement.getNetAmount().signum() > 0)
                .filter(settlement -> requestedSettlementIds == null
                        || requestedSettlementIds.isEmpty()
                        || requestedSettlementIds.contains(settlement.getId()))
                .limit(limit)
                .toList();

        if (selectedSettlements.isEmpty()) {
            throw new BadRequestException("No pending settlements are available for payout.");
        }

        if (requestedSettlementIds != null && !requestedSettlementIds.isEmpty()) {
            Set<Long> selectedIds = selectedSettlements.stream().map(SettlementEntity::getId).collect(Collectors.toSet());
            if (!selectedIds.containsAll(requestedSettlementIds)) {
                throw new BadRequestException("One or more requested settlements are not available for payout.");
            }
        }

        PayoutBatchEntity batch = new PayoutBatchEntity();
        batch.setBatchReference(generateBatchReference());
        batch.setPayoutStatus(STATUS_INITIATED);
        batch.setTotalAmount(selectedSettlements.stream()
                .map(settlement -> zeroIfNull(settlement.getNetAmount()))
                .reduce(ZERO, BigDecimal::add));
        batch.setInitiatedAt(LocalDateTime.now());
        batch = payoutBatchRepository.save(batch);

        for (SettlementEntity settlement : selectedSettlements) {
            settlement.setStatus(STATUS_PROCESSING);
            settlementRepository.save(settlement);

            PayoutBatchItemEntity item = new PayoutBatchItemEntity();
            item.setPayoutBatchId(batch.getId());
            item.setSettlementId(settlement.getId());
            item.setPayoutAmount(zeroIfNull(settlement.getNetAmount()));
            item.setPayoutStatus(STATUS_INITIATED);
            payoutBatchItemRepository.save(item);
        }

        return toData(batch, payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(batch.getId()));
    }

    @Transactional(readOnly = true)
    public PayoutBatchData getBatch(Long batchId) {
        PayoutBatchEntity batch = loadBatch(batchId);
        return toData(batch, payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(batchId));
    }

    @Transactional
    public PayoutBatchData completeBatch(Long batchId, CompletePayoutBatchRequest request) {
        PayoutBatchEntity batch = loadBatch(batchId);
        List<PayoutBatchItemEntity> items = payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(batchId);
        ensureBatchHasItems(items);
        Set<Long> targetSettlementIds = selectTargetSettlementIds(items, request == null ? null : request.settlementIds());

        for (PayoutBatchItemEntity item : items) {
            if (!targetSettlementIds.contains(item.getSettlementId())) {
                continue;
            }
            item.setPayoutStatus(STATUS_SUCCESS);
            item.setFailureReason(null);
            payoutBatchItemRepository.save(item);

            SettlementEntity settlement = settlementRepository.findById(item.getSettlementId())
                    .orElseThrow(() -> new ResourceNotFoundException("Settlement not found"));
            settlement.setStatus(STATUS_PAID);
            settlement.setPaidAt(LocalDateTime.now());
            settlementRepository.save(settlement);
        }

        refreshBatchStatus(batch, payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(batchId));
        return toData(batch, payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(batchId));
    }

    @Transactional
    public PayoutBatchData failBatch(Long batchId, FailPayoutBatchRequest request) {
        PayoutBatchEntity batch = loadBatch(batchId);
        List<PayoutBatchItemEntity> items = payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(batchId);
        ensureBatchHasItems(items);
        Set<Long> targetSettlementIds = selectTargetSettlementIds(items, request == null ? null : request.settlementIds());
        String reason = request == null ? null : normalizeBlank(request.reason());
        if (reason == null) {
            reason = "Payout failed";
        }

        for (PayoutBatchItemEntity item : items) {
            if (!targetSettlementIds.contains(item.getSettlementId())) {
                continue;
            }
            item.setPayoutStatus(STATUS_FAILED);
            item.setFailureReason(reason);
            payoutBatchItemRepository.save(item);

            SettlementEntity settlement = settlementRepository.findById(item.getSettlementId())
                    .orElseThrow(() -> new ResourceNotFoundException("Settlement not found"));
            settlement.setStatus(STATUS_PENDING);
            settlement.setPaidAt(null);
            settlementRepository.save(settlement);
        }

        refreshBatchStatus(batch, payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(batchId));
        return toData(batch, payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(batchId));
    }

    private void refreshBatchStatus(PayoutBatchEntity batch, List<PayoutBatchItemEntity> items) {
        boolean anyInitiated = items.stream().anyMatch(item -> STATUS_INITIATED.equalsIgnoreCase(item.getPayoutStatus()));
        boolean anySuccess = items.stream().anyMatch(item -> STATUS_SUCCESS.equalsIgnoreCase(item.getPayoutStatus()));
        boolean anyFailed = items.stream().anyMatch(item -> STATUS_FAILED.equalsIgnoreCase(item.getPayoutStatus()));

        if (anyInitiated) {
            batch.setPayoutStatus(STATUS_PROCESSING);
            batch.setCompletedAt(null);
        } else if (anySuccess && anyFailed) {
            batch.setPayoutStatus(STATUS_PROCESSING);
            batch.setCompletedAt(null);
        } else if (anySuccess) {
            batch.setPayoutStatus(STATUS_SUCCESS);
            batch.setCompletedAt(LocalDateTime.now());
        } else if (anyFailed) {
            batch.setPayoutStatus(STATUS_FAILED);
            batch.setCompletedAt(LocalDateTime.now());
        }
        payoutBatchRepository.save(batch);
    }

    private Set<Long> selectTargetSettlementIds(List<PayoutBatchItemEntity> items, List<Long> requestedSettlementIds) {
        Set<Long> availableSettlementIds = items.stream()
                .map(PayoutBatchItemEntity::getSettlementId)
                .collect(Collectors.toSet());
        if (requestedSettlementIds == null || requestedSettlementIds.isEmpty()) {
            return availableSettlementIds;
        }
        Set<Long> requested = requestedSettlementIds.stream().collect(Collectors.toSet());
        if (!availableSettlementIds.containsAll(requested)) {
            throw new BadRequestException("One or more settlements do not belong to this payout batch.");
        }
        return requested;
    }

    private void ensureBatchHasItems(List<PayoutBatchItemEntity> items) {
        if (items.isEmpty()) {
            throw new BadRequestException("Payout batch has no payout items.");
        }
    }

    private PayoutBatchEntity loadBatch(Long batchId) {
        return payoutBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Payout batch not found"));
    }

    private PayoutBatchData toData(PayoutBatchEntity batch, List<PayoutBatchItemEntity> items) {
        List<PayoutBatchItemData> itemData = items.stream()
                .map(item -> new PayoutBatchItemData(
                        item.getId(),
                        item.getSettlementId(),
                        item.getPayoutAmount(),
                        item.getPayoutStatus(),
                        item.getFailureReason()
                ))
                .toList();
        return new PayoutBatchData(
                batch.getId(),
                batch.getBatchReference(),
                batch.getPayoutStatus(),
                batch.getTotalAmount(),
                itemData.size(),
                batch.getInitiatedAt(),
                batch.getCompletedAt(),
                itemData
        );
    }

    private String generateBatchReference() {
        return "PBT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(2);
    }

    private String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
