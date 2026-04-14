package com.msa.booking.payment.modules.settlement.service;

import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.CompletePayoutBatchRequest;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.CreatePayoutBatchRequest;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.FailPayoutBatchRequest;
import com.msa.booking.payment.modules.settlement.dto.PayoutBatchDtos.PayoutBatchData;
import com.msa.booking.payment.persistence.entity.PayoutBatchEntity;
import com.msa.booking.payment.persistence.entity.PayoutBatchItemEntity;
import com.msa.booking.payment.persistence.entity.SettlementEntity;
import com.msa.booking.payment.persistence.repository.PayoutBatchItemRepository;
import com.msa.booking.payment.persistence.repository.PayoutBatchRepository;
import com.msa.booking.payment.persistence.repository.SettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayoutBatchServiceTest {
    @Mock
    private PayoutBatchRepository payoutBatchRepository;
    @Mock
    private PayoutBatchItemRepository payoutBatchItemRepository;
    @Mock
    private SettlementRepository settlementRepository;

    private PayoutBatchService service;

    @BeforeEach
    void setUp() {
        service = new PayoutBatchService(
                payoutBatchRepository,
                payoutBatchItemRepository,
                settlementRepository
        );
    }

    @Test
    void createBatchMovesPendingSettlementsToProcessing() {
        SettlementEntity shopSettlement = settlement(11L, "SHOP", BigDecimal.valueOf(180));
        SettlementEntity labourSettlement = settlement(12L, "LABOUR", BigDecimal.valueOf(450));

        PayoutBatchEntity savedBatch = new PayoutBatchEntity();
        savedBatch.setId(41L);
        savedBatch.setBatchReference("PBT-41");
        savedBatch.setPayoutStatus("INITIATED");
        savedBatch.setTotalAmount(BigDecimal.valueOf(630).setScale(2));
        savedBatch.setInitiatedAt(LocalDateTime.now());

        when(settlementRepository.findAllByStatusOrderByIdAsc("PENDING")).thenReturn(List.of(shopSettlement, labourSettlement));
        when(payoutBatchRepository.save(any(PayoutBatchEntity.class))).thenReturn(savedBatch);
        when(payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(41L)).thenReturn(List.of(
                payoutItem(81L, 41L, 11L, BigDecimal.valueOf(180), "INITIATED"),
                payoutItem(82L, 41L, 12L, BigDecimal.valueOf(450), "INITIATED")
        ));

        PayoutBatchData data = service.createBatch(new CreatePayoutBatchRequest(null, 10, null));

        assertEquals(41L, data.payoutBatchId());
        assertEquals("INITIATED", data.payoutStatus());
        assertEquals(2, data.settlementCount());
        verify(settlementRepository, times(2)).save(any(SettlementEntity.class));
        assertEquals("PROCESSING", shopSettlement.getStatus());
        assertEquals("PROCESSING", labourSettlement.getStatus());
    }

    @Test
    void completeBatchMarksSettlementsPaid() {
        PayoutBatchEntity batch = new PayoutBatchEntity();
        batch.setId(41L);
        batch.setBatchReference("PBT-41");
        batch.setPayoutStatus("INITIATED");
        batch.setTotalAmount(BigDecimal.valueOf(180).setScale(2));
        batch.setInitiatedAt(LocalDateTime.now().minusMinutes(5));

        SettlementEntity settlement = settlement(11L, "SHOP", BigDecimal.valueOf(180));
        settlement.setStatus("PROCESSING");

        PayoutBatchItemEntity item = payoutItem(81L, 41L, 11L, BigDecimal.valueOf(180), "INITIATED");

        when(payoutBatchRepository.findById(41L)).thenReturn(Optional.of(batch));
        when(payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(41L)).thenReturn(List.of(item));
        when(settlementRepository.findById(11L)).thenReturn(Optional.of(settlement));

        PayoutBatchData data = service.completeBatch(41L, new CompletePayoutBatchRequest(List.of(11L)));

        assertEquals("SUCCESS", data.payoutStatus());
        assertEquals("PAID", settlement.getStatus());
        verify(settlementRepository).save(settlement);
    }

    @Test
    void failBatchReturnsSettlementsToPending() {
        PayoutBatchEntity batch = new PayoutBatchEntity();
        batch.setId(42L);
        batch.setBatchReference("PBT-42");
        batch.setPayoutStatus("PROCESSING");
        batch.setTotalAmount(BigDecimal.valueOf(450).setScale(2));
        batch.setInitiatedAt(LocalDateTime.now().minusMinutes(5));

        SettlementEntity settlement = settlement(12L, "LABOUR", BigDecimal.valueOf(450));
        settlement.setStatus("PROCESSING");
        settlement.setPaidAt(LocalDateTime.now());

        PayoutBatchItemEntity item = payoutItem(82L, 42L, 12L, BigDecimal.valueOf(450), "INITIATED");

        when(payoutBatchRepository.findById(42L)).thenReturn(Optional.of(batch));
        when(payoutBatchItemRepository.findAllByPayoutBatchIdOrderByIdAsc(42L)).thenReturn(List.of(item));
        when(settlementRepository.findById(12L)).thenReturn(Optional.of(settlement));

        PayoutBatchData data = service.failBatch(42L, new FailPayoutBatchRequest("Bank rejected payout", List.of(12L)));

        assertEquals("FAILED", data.payoutStatus());
        assertEquals("PENDING", settlement.getStatus());
        assertEquals(null, settlement.getPaidAt());

        ArgumentCaptor<PayoutBatchItemEntity> itemCaptor = ArgumentCaptor.forClass(PayoutBatchItemEntity.class);
        verify(payoutBatchItemRepository).save(itemCaptor.capture());
        assertEquals("FAILED", itemCaptor.getValue().getPayoutStatus());
        assertEquals("Bank rejected payout", itemCaptor.getValue().getFailureReason());
    }

    private SettlementEntity settlement(Long id, String beneficiaryType, BigDecimal netAmount) {
        SettlementEntity settlement = new SettlementEntity();
        settlement.setId(id);
        settlement.setBeneficiaryType(beneficiaryType);
        settlement.setBeneficiaryId(id + 1000);
        settlement.setGrossAmount(netAmount.setScale(2));
        settlement.setCommissionAmount(BigDecimal.ZERO.setScale(2));
        settlement.setTaxAmount(BigDecimal.ZERO.setScale(2));
        settlement.setAdjustmentAmount(BigDecimal.ZERO.setScale(2));
        settlement.setRefundDeductionAmount(BigDecimal.ZERO.setScale(2));
        settlement.setNetAmount(netAmount.setScale(2));
        settlement.setStatus("PENDING");
        return settlement;
    }

    private PayoutBatchItemEntity payoutItem(Long id, Long batchId, Long settlementId, BigDecimal amount, String status) {
        PayoutBatchItemEntity item = new PayoutBatchItemEntity();
        item.setId(id);
        item.setPayoutBatchId(batchId);
        item.setSettlementId(settlementId);
        item.setPayoutAmount(amount.setScale(2));
        item.setPayoutStatus(status);
        return item;
    }
}
