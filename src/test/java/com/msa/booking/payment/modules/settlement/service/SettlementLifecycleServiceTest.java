package com.msa.booking.payment.modules.settlement.service;

import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.integration.shoporders.dto.ShopOrdersRuntimeSyncDtos;
import com.msa.booking.payment.order.service.ShopOrderFinanceContextService;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.SettlementCycleEntity;
import com.msa.booking.payment.persistence.entity.SettlementEntity;
import com.msa.booking.payment.persistence.entity.SettlementLineItemEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.SettlementCycleRepository;
import com.msa.booking.payment.persistence.repository.SettlementLineItemRepository;
import com.msa.booking.payment.persistence.repository.SettlementRepository;
import com.msa.booking.payment.storage.BillingDocumentStorageService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementLifecycleServiceTest {
    @Mock
    private SettlementCycleRepository settlementCycleRepository;
    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private SettlementLineItemRepository settlementLineItemRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ShopOrderFinanceContextService shopOrderFinanceContextService;
    @Mock
    private BillingDocumentStorageService billingDocumentStorageService;

    private SettlementLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new SettlementLifecycleService(
                settlementCycleRepository,
                settlementRepository,
                settlementLineItemRepository,
                bookingRepository,
                shopOrderFinanceContextService,
                billingDocumentStorageService
        );
    }

    @Test
    void recordSuccessfulOrderPaymentCreatesDailyShopSettlement() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(100L);
        payment.setPayableType(PayableType.SHOP_ORDER);
        payment.setPayableId(44L);
        payment.setAmount(BigDecimal.valueOf(199));
        payment.setCompletedAt(LocalDateTime.of(2026, 4, 14, 12, 0));

        SettlementCycleEntity cycle = new SettlementCycleEntity();
        cycle.setId(10L);

        SettlementEntity savedSettlement = new SettlementEntity();
        savedSettlement.setId(20L);
        savedSettlement.setGrossAmount(BigDecimal.ZERO.setScale(2));
        savedSettlement.setCommissionAmount(BigDecimal.ZERO.setScale(2));
        savedSettlement.setNetAmount(BigDecimal.ZERO.setScale(2));

        when(settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType("SHOP_ORDER", 44L, "GROSS")).thenReturn(false);
        when(shopOrderFinanceContextService.loadRequired(44L)).thenReturn(orderContext());
        when(settlementCycleRepository.findByCycleTypeAndPeriodStartAndPeriodEnd("DAILY", LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 14)))
                .thenReturn(Optional.of(cycle));
        when(settlementRepository.findBySettlementCycleIdAndBeneficiaryTypeAndBeneficiaryId(10L, "SHOP", 300L))
                .thenReturn(Optional.empty());
        when(settlementRepository.save(any(SettlementEntity.class))).thenReturn(savedSettlement);

        service.recordSuccessfulPayment(payment);

        ArgumentCaptor<SettlementEntity> settlementCaptor = ArgumentCaptor.forClass(SettlementEntity.class);
        verify(settlementRepository, times(2)).save(settlementCaptor.capture());
        SettlementEntity updatedSettlement = settlementCaptor.getAllValues().get(1);
        assertEquals(BigDecimal.valueOf(199).setScale(2), updatedSettlement.getGrossAmount());
        assertEquals(BigDecimal.valueOf(19).setScale(2), updatedSettlement.getCommissionAmount());
        assertEquals(BigDecimal.valueOf(180).setScale(2), updatedSettlement.getNetAmount());
        verify(settlementLineItemRepository, times(2)).save(any());
    }

    @Test
    void recordSuccessfulPaymentSkipsWhenSettlementAlreadyPosted() {
        PaymentEntity payment = new PaymentEntity();
        payment.setPayableType(PayableType.SHOP_ORDER);
        payment.setPayableId(44L);

        when(settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType("SHOP_ORDER", 44L, "GROSS")).thenReturn(true);

        service.recordSuccessfulPayment(payment);

        verify(shopOrderFinanceContextService, never()).loadRequired(any());
        verify(settlementRepository, never()).save(any());
        verify(settlementLineItemRepository, never()).save(any());
    }

    @Test
    void recordSuccessfulOrderRefundAdjustsExistingSettlement() {
        PaymentEntity payment = new PaymentEntity();
        payment.setPayableType(PayableType.SHOP_ORDER);
        payment.setPayableId(44L);

        SettlementLineItemEntity grossLine = new SettlementLineItemEntity();
        grossLine.setSettlementId(20L);

        SettlementEntity settlement = new SettlementEntity();
        settlement.setId(20L);
        settlement.setGrossAmount(BigDecimal.valueOf(199).setScale(2));
        settlement.setCommissionAmount(BigDecimal.valueOf(19).setScale(2));
        settlement.setRefundDeductionAmount(BigDecimal.ZERO.setScale(2));
        settlement.setNetAmount(BigDecimal.valueOf(180).setScale(2));

        when(settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType("SHOP_ORDER", 44L, "REFUND")).thenReturn(false);
        when(shopOrderFinanceContextService.loadRequired(44L)).thenReturn(orderContext());
        when(settlementLineItemRepository.findTopBySourceTypeAndSourceIdAndLineTypeOrderByIdAsc("SHOP_ORDER", 44L, "GROSS"))
                .thenReturn(Optional.of(grossLine));
        when(settlementRepository.findById(20L)).thenReturn(Optional.of(settlement));

        service.recordSuccessfulRefund(payment, BigDecimal.valueOf(50));

        ArgumentCaptor<SettlementEntity> settlementCaptor = ArgumentCaptor.forClass(SettlementEntity.class);
        verify(settlementRepository).save(settlementCaptor.capture());
        SettlementEntity updatedSettlement = settlementCaptor.getValue();
        assertEquals(BigDecimal.valueOf(50).setScale(2), updatedSettlement.getRefundDeductionAmount());
        assertEquals(BigDecimal.valueOf(130).setScale(2), updatedSettlement.getNetAmount());
        verify(settlementLineItemRepository).save(any());
    }

    private ShopOrdersRuntimeSyncDtos.OrderFinanceContextData orderContext() {
        return new ShopOrdersRuntimeSyncDtos.OrderFinanceContextData(
                44L,
                "ORD-44",
                300L,
                88L,
                "PAYMENT_COMPLETED",
                "PAID",
                BigDecimal.valueOf(150),
                BigDecimal.valueOf(49),
                BigDecimal.valueOf(199),
                BigDecimal.valueOf(19),
                "INR"
        );
    }
}
