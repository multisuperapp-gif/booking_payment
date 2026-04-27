package com.msa.booking.payment.modules.settlement.service;

import com.msa.booking.payment.common.exception.ResourceNotFoundException;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.SettlementCycleEntity;
import com.msa.booking.payment.persistence.entity.SettlementEntity;
import com.msa.booking.payment.persistence.entity.SettlementLineItemEntity;
import com.msa.booking.payment.order.service.ShopOrderFinanceContextService;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.SettlementCycleRepository;
import com.msa.booking.payment.persistence.repository.SettlementLineItemRepository;
import com.msa.booking.payment.persistence.repository.SettlementRepository;
import com.msa.booking.payment.storage.BillingDocumentStorageService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementLifecycleService {
    private static final String DAILY = "DAILY";
    private static final String OPEN = "OPEN";
    private static final String PENDING = "PENDING";
    private static final String SOURCE_ORDER = "SHOP_ORDER";
    private static final String SOURCE_BOOKING = "BOOKING";
    private static final String LINE_GROSS = "GROSS";
    private static final String LINE_COMMISSION = "COMMISSION";
    private static final String LINE_REFUND = "REFUND";
    private static final String LINE_ADJUSTMENT = "ADJUSTMENT";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final SettlementCycleRepository settlementCycleRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementLineItemRepository settlementLineItemRepository;
    private final BookingRepository bookingRepository;
    private final ShopOrderFinanceContextService shopOrderFinanceContextService;
    private final BillingDocumentStorageService billingDocumentStorageService;

    public SettlementLifecycleService(
            SettlementCycleRepository settlementCycleRepository,
            SettlementRepository settlementRepository,
            SettlementLineItemRepository settlementLineItemRepository,
            BookingRepository bookingRepository,
            ShopOrderFinanceContextService shopOrderFinanceContextService,
            BillingDocumentStorageService billingDocumentStorageService
    ) {
        this.settlementCycleRepository = settlementCycleRepository;
        this.settlementRepository = settlementRepository;
        this.settlementLineItemRepository = settlementLineItemRepository;
        this.bookingRepository = bookingRepository;
        this.shopOrderFinanceContextService = shopOrderFinanceContextService;
        this.billingDocumentStorageService = billingDocumentStorageService;
    }

    @Transactional
    public void recordSuccessfulPayment(PaymentEntity payment) {
        if (payment.getPayableType() == PayableType.SHOP_ORDER) {
            recordOrderSettlement(payment);
            return;
        }
        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            if (booking.getBookingType() == com.msa.booking.payment.domain.enums.BookingFlowType.LABOUR) {
                return;
            }
            recordBookingSettlement(payment);
        }
    }

    @Transactional
    public void recordSuccessfulRefund(PaymentEntity payment, BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.signum() <= 0) {
            return;
        }
        if (payment.getPayableType() == PayableType.SHOP_ORDER) {
            recordOrderRefund(payment, refundAmount);
            return;
        }
        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            if (booking.getBookingType() == com.msa.booking.payment.domain.enums.BookingFlowType.LABOUR) {
                return;
            }
            recordBookingRefund(payment, refundAmount);
        }
    }

    @Transactional
    public void recordLabourCancellationShare(BookingEntity booking, BigDecimal shareAmount, String remark) {
        if (booking == null || shareAmount == null || shareAmount.signum() <= 0) {
            return;
        }
        if (settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType(
                SOURCE_BOOKING,
                booking.getId(),
                LINE_ADJUSTMENT
        )) {
            return;
        }
        SettlementCycleEntity cycle = resolveDailyCycle(LocalDateTime.now());
        SettlementEntity settlement = resolveSettlement(
                cycle.getId(),
                resolveBookingBeneficiaryType(booking.getProviderEntityType()),
                booking.getProviderEntityId()
        );
        settlement.setAdjustmentAmount(amountOrZero(settlement.getAdjustmentAmount()).add(shareAmount));
        settlement.setNetAmount(amountOrZero(settlement.getNetAmount()).add(shareAmount));
        settlementRepository.save(settlement);
        saveLineItem(
                settlement.getId(),
                SOURCE_BOOKING,
                booking.getId(),
                LINE_ADJUSTMENT,
                shareAmount.setScale(2, RoundingMode.HALF_UP),
                remark == null || remark.isBlank() ? booking.getBookingCode() : remark
        );
        billingDocumentStorageService.storeSettlementStatement(settlement, "Labour cancellation settlement statement.");
    }

    private void recordOrderSettlement(PaymentEntity payment) {
        Long orderId = payment.getPayableId();
        if (settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType(SOURCE_ORDER, orderId, LINE_GROSS)) {
            return;
        }

        var order = shopOrderFinanceContextService.loadRequired(orderId);
        SettlementCycleEntity cycle = resolveDailyCycle(payment.getCompletedAt());
        SettlementEntity settlement = resolveSettlement(cycle.getId(), "SHOP", order.shopId());

        BigDecimal gross = amountOrFallback(order.totalAmount(), payment.getAmount());
        BigDecimal commission = amountOrZero(order.platformFeeAmount());

        appendAmounts(settlement, gross, commission);
        settlementRepository.save(settlement);

        saveLineItem(settlement.getId(), SOURCE_ORDER, orderId, LINE_GROSS, gross, order.orderCode());
        if (commission.signum() > 0) {
            saveLineItem(settlement.getId(), SOURCE_ORDER, orderId, LINE_COMMISSION, commission.negate(), order.orderCode());
        }
        billingDocumentStorageService.storeSettlementStatement(settlement, "Shop settlement statement.");
    }

    private void recordBookingSettlement(PaymentEntity payment) {
        Long bookingId = payment.getPayableId();
        if (settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType(SOURCE_BOOKING, bookingId, LINE_GROSS)) {
            return;
        }

        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
        SettlementCycleEntity cycle = resolveDailyCycle(payment.getCompletedAt());
        SettlementEntity settlement = resolveSettlement(
                cycle.getId(),
                resolveBookingBeneficiaryType(booking.getProviderEntityType()),
                booking.getProviderEntityId()
        );

        BigDecimal gross = resolveBookingGrossAmount(booking, payment);
        BigDecimal commission = amountOrZero(booking.getPlatformFeeAmount());

        appendAmounts(settlement, gross, commission);
        settlementRepository.save(settlement);

        saveLineItem(settlement.getId(), SOURCE_BOOKING, bookingId, LINE_GROSS, gross, booking.getBookingCode());
        if (commission.signum() > 0) {
            saveLineItem(settlement.getId(), SOURCE_BOOKING, bookingId, LINE_COMMISSION, commission.negate(), booking.getBookingCode());
        }
        billingDocumentStorageService.storeSettlementStatement(settlement, "Booking settlement statement.");
    }

    private void recordOrderRefund(PaymentEntity payment, BigDecimal refundAmount) {
        Long orderId = payment.getPayableId();
        if (settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType(SOURCE_ORDER, orderId, LINE_REFUND)) {
            return;
        }
        var order = shopOrderFinanceContextService.loadRequired(orderId);
        SettlementEntity settlement = resolveSettlementForRefund(
                SOURCE_ORDER,
                orderId,
                payment.getCompletedAt(),
                "SHOP",
                order.shopId()
        );
        appendRefund(settlement, refundAmount);
        settlementRepository.save(settlement);
        saveLineItem(settlement.getId(), SOURCE_ORDER, orderId, LINE_REFUND, refundAmount.negate(), order.orderCode());
        billingDocumentStorageService.storeSettlementStatement(settlement, "Shop refund updated settlement statement.");
    }

    private void recordBookingRefund(PaymentEntity payment, BigDecimal refundAmount) {
        Long bookingId = payment.getPayableId();
        if (settlementLineItemRepository.existsBySourceTypeAndSourceIdAndLineType(SOURCE_BOOKING, bookingId, LINE_REFUND)) {
            return;
        }
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
        SettlementEntity settlement = resolveSettlementForRefund(
                SOURCE_BOOKING,
                bookingId,
                payment.getCompletedAt(),
                resolveBookingBeneficiaryType(booking.getProviderEntityType()),
                booking.getProviderEntityId()
        );
        appendRefund(settlement, refundAmount);
        settlementRepository.save(settlement);
        saveLineItem(settlement.getId(), SOURCE_BOOKING, bookingId, LINE_REFUND, refundAmount.negate(), booking.getBookingCode());
        billingDocumentStorageService.storeSettlementStatement(settlement, "Booking refund updated settlement statement.");
    }

    private SettlementCycleEntity resolveDailyCycle(LocalDateTime completedAt) {
        LocalDate cycleDate = (completedAt == null ? LocalDate.now() : completedAt.toLocalDate());
        return settlementCycleRepository.findByCycleTypeAndPeriodStartAndPeriodEnd(DAILY, cycleDate, cycleDate)
                .orElseGet(() -> {
                    SettlementCycleEntity cycle = new SettlementCycleEntity();
                    cycle.setCycleType(DAILY);
                    cycle.setPeriodStart(cycleDate);
                    cycle.setPeriodEnd(cycleDate);
                    cycle.setStatus(OPEN);
                    return settlementCycleRepository.save(cycle);
                });
    }

    private SettlementEntity resolveSettlement(Long cycleId, String beneficiaryType, Long beneficiaryId) {
        return settlementRepository.findBySettlementCycleIdAndBeneficiaryTypeAndBeneficiaryId(
                cycleId,
                beneficiaryType,
                beneficiaryId
        ).orElseGet(() -> {
            SettlementEntity settlement = new SettlementEntity();
            settlement.setSettlementCode(generateSettlementCode());
            settlement.setSettlementCycleId(cycleId);
            settlement.setBeneficiaryType(beneficiaryType);
            settlement.setBeneficiaryId(beneficiaryId);
            settlement.setGrossAmount(ZERO);
            settlement.setCommissionAmount(ZERO);
            settlement.setTaxAmount(ZERO);
            settlement.setAdjustmentAmount(ZERO);
            settlement.setRefundDeductionAmount(ZERO);
            settlement.setNetAmount(ZERO);
            settlement.setStatus(PENDING);
            return settlementRepository.save(settlement);
        });
    }

    private void appendAmounts(SettlementEntity settlement, BigDecimal gross, BigDecimal commission) {
        settlement.setGrossAmount(amountOrZero(settlement.getGrossAmount()).add(gross));
        settlement.setCommissionAmount(amountOrZero(settlement.getCommissionAmount()).add(commission));
        settlement.setNetAmount(amountOrZero(settlement.getNetAmount()).add(gross.subtract(commission)));
    }

    private void appendRefund(SettlementEntity settlement, BigDecimal refundAmount) {
        settlement.setRefundDeductionAmount(amountOrZero(settlement.getRefundDeductionAmount()).add(refundAmount));
        settlement.setNetAmount(amountOrZero(settlement.getNetAmount()).subtract(refundAmount));
    }

    private SettlementEntity resolveSettlementForRefund(
            String sourceType,
            Long sourceId,
            LocalDateTime completedAt,
            String beneficiaryType,
            Long beneficiaryId
    ) {
        return settlementLineItemRepository.findTopBySourceTypeAndSourceIdAndLineTypeOrderByIdAsc(sourceType, sourceId, LINE_GROSS)
                .flatMap(lineItem -> settlementRepository.findById(lineItem.getSettlementId()))
                .orElseGet(() -> {
                    SettlementCycleEntity cycle = resolveDailyCycle(completedAt);
                    return resolveSettlement(cycle.getId(), beneficiaryType, beneficiaryId);
                });
    }

    private void saveLineItem(
            Long settlementId,
            String sourceType,
            Long sourceId,
            String lineType,
            BigDecimal amount,
            String sourceCode
    ) {
        SettlementLineItemEntity lineItem = new SettlementLineItemEntity();
        lineItem.setSettlementId(settlementId);
        lineItem.setSourceType(sourceType);
        lineItem.setSourceId(sourceId);
        lineItem.setLineType(lineType);
        lineItem.setAmount(amount);
        lineItem.setRemarks(sourceCode);
        settlementLineItemRepository.save(lineItem);
    }

    private String resolveBookingBeneficiaryType(ProviderEntityType providerEntityType) {
        return providerEntityType == ProviderEntityType.LABOUR ? "LABOUR" : "PROVIDER";
    }

    private BigDecimal resolveBookingGrossAmount(BookingEntity booking, PaymentEntity payment) {
        return amountOrFallback(
                booking.getTotalFinalAmount(),
                amountOrFallback(booking.getTotalEstimatedAmount(), payment.getAmount())
        );
    }

    private BigDecimal amountOrFallback(BigDecimal preferred, BigDecimal fallback) {
        if (preferred != null) {
            return preferred.setScale(2);
        }
        return amountOrZero(fallback);
    }

    private BigDecimal amountOrZero(BigDecimal amount) {
        if (amount == null) {
            return ZERO;
        }
        return amount.setScale(2);
    }

    private String generateSettlementCode() {
        return "SET-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
