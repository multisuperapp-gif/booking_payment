package com.msa.booking.payment.modules.settlement.service;

import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.OrderEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.SettlementCycleEntity;
import com.msa.booking.payment.persistence.entity.SettlementEntity;
import com.msa.booking.payment.persistence.entity.SettlementLineItemEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.OrderRepository;
import com.msa.booking.payment.persistence.repository.SettlementCycleRepository;
import com.msa.booking.payment.persistence.repository.SettlementLineItemRepository;
import com.msa.booking.payment.persistence.repository.SettlementRepository;
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
    private OrderRepository orderRepository;
    @Mock
    private BookingRepository bookingRepository;

    private SettlementLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new SettlementLifecycleService(
                settlementCycleRepository,
                settlementRepository,
                settlementLineItemRepository,
                orderRepository,
                bookingRepository
        );
    }

    @Test
    void recordSuccessfulOrderPaymentCreatesDailyShopSettlement() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(100L);
        payment.setPayableType(PayableType.ORDER);
        payment.setPayableId(44L);
        payment.setAmount(BigDecimal.valueOf(199));
        payment.setCompletedAt(LocalDateTime.of(2026, 4, 14, 12, 0));

        OrderEntity order = new OrderEntity();
        order.setId(44L);
        order.setOrderCode("ORD-44");
        order.setShopId(300L);
        order.setTotalAmount(BigDecimal.valueOf(199));
        order.setPlatformFeeAmount(BigDecimal.valueOf(19));

        SettlementCycleEntity cycle = new SettlementCycleEntity();
        cycle.setId(10L);

        SettlementEntity savedSettlement = new SettlementEntity();
        savedSettlement.setId(20L);
        savedSettlement.setGrossAmount(BigDecimal.ZERO.setScale(2));
        savedSettlement.setCommissionAmount(BigDecimal.ZERO.setScale(2));
        savedSettlement.setNetAmount(BigDecimal.ZERO.setScale(2));

        when(settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType("ORDER", 44L, "GROSS")).thenReturn(false);
        when(orderRepository.findById(44L)).thenReturn(Optional.of(order));
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
    void recordSuccessfulBookingPaymentCreatesLabourSettlement() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(101L);
        payment.setPayableType(PayableType.BOOKING);
        payment.setPayableId(55L);
        payment.setAmount(BigDecimal.valueOf(499));
        payment.setCompletedAt(LocalDateTime.of(2026, 4, 14, 18, 0));

        BookingEntity booking = new BookingEntity();
        booking.setId(55L);
        booking.setBookingCode("BKG-55");
        booking.setProviderEntityType(ProviderEntityType.LABOUR);
        booking.setProviderEntityId(901L);
        booking.setTotalFinalAmount(BigDecimal.valueOf(499));
        booking.setPlatformFeeAmount(BigDecimal.valueOf(49));

        SettlementCycleEntity cycle = new SettlementCycleEntity();
        cycle.setId(11L);

        SettlementEntity savedSettlement = new SettlementEntity();
        savedSettlement.setId(21L);
        savedSettlement.setGrossAmount(BigDecimal.ZERO.setScale(2));
        savedSettlement.setCommissionAmount(BigDecimal.ZERO.setScale(2));
        savedSettlement.setNetAmount(BigDecimal.ZERO.setScale(2));

        when(settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType("BOOKING", 55L, "GROSS")).thenReturn(false);
        when(bookingRepository.findById(55L)).thenReturn(Optional.of(booking));
        when(settlementCycleRepository.findByCycleTypeAndPeriodStartAndPeriodEnd("DAILY", LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 14)))
                .thenReturn(Optional.of(cycle));
        when(settlementRepository.findBySettlementCycleIdAndBeneficiaryTypeAndBeneficiaryId(11L, "LABOUR", 901L))
                .thenReturn(Optional.empty());
        when(settlementRepository.save(any(SettlementEntity.class))).thenReturn(savedSettlement);

        service.recordSuccessfulPayment(payment);

        ArgumentCaptor<SettlementEntity> settlementCaptor = ArgumentCaptor.forClass(SettlementEntity.class);
        verify(settlementRepository, times(2)).save(settlementCaptor.capture());
        SettlementEntity updatedSettlement = settlementCaptor.getAllValues().get(1);
        assertEquals(BigDecimal.valueOf(499).setScale(2), updatedSettlement.getGrossAmount());
        assertEquals(BigDecimal.valueOf(49).setScale(2), updatedSettlement.getCommissionAmount());
        assertEquals(BigDecimal.valueOf(450).setScale(2), updatedSettlement.getNetAmount());
        verify(settlementLineItemRepository, times(2)).save(any());
    }

    @Test
    void recordSuccessfulPaymentSkipsWhenSettlementAlreadyPosted() {
        PaymentEntity payment = new PaymentEntity();
        payment.setPayableType(PayableType.ORDER);
        payment.setPayableId(44L);

        when(settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType("ORDER", 44L, "GROSS")).thenReturn(true);

        service.recordSuccessfulPayment(payment);

        verify(orderRepository, never()).findById(any());
        verify(settlementRepository, never()).save(any());
        verify(settlementLineItemRepository, never()).save(any());
    }

    @Test
    void recordSuccessfulOrderRefundAdjustsExistingSettlement() {
        PaymentEntity payment = new PaymentEntity();
        payment.setPayableType(PayableType.ORDER);
        payment.setPayableId(44L);

        OrderEntity order = new OrderEntity();
        order.setId(44L);
        order.setOrderCode("ORD-44");
        order.setShopId(300L);

        SettlementLineItemEntity grossLine = new SettlementLineItemEntity();
        grossLine.setSettlementId(20L);

        SettlementEntity settlement = new SettlementEntity();
        settlement.setId(20L);
        settlement.setGrossAmount(BigDecimal.valueOf(199).setScale(2));
        settlement.setCommissionAmount(BigDecimal.valueOf(19).setScale(2));
        settlement.setRefundDeductionAmount(BigDecimal.ZERO.setScale(2));
        settlement.setNetAmount(BigDecimal.valueOf(180).setScale(2));

        when(settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType("ORDER", 44L, "REFUND")).thenReturn(false);
        when(orderRepository.findById(44L)).thenReturn(Optional.of(order));
        when(settlementLineItemRepository.findTopBySourceTypeAndSourceIdAndLineTypeOrderByIdAsc("ORDER", 44L, "GROSS"))
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
}
