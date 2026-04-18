package com.msa.booking.payment.modules.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.common.exception.ResourceNotFoundException;
import com.msa.booking.payment.config.RazorpayProperties;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.domain.enums.BookingLifecycleStatus;
import com.msa.booking.payment.domain.enums.OrderLifecycleStatus;
import com.msa.booking.payment.domain.enums.PayablePaymentStatus;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.domain.enums.PaymentAttemptStatus;
import com.msa.booking.payment.domain.enums.PaymentLifecycleStatus;
import com.msa.booking.payment.domain.enums.PaymentTransactionStatus;
import com.msa.booking.payment.domain.enums.PaymentTransactionType;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentInitiateRequest;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentInitiateResponse;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentFailureRequest;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentStatusResponse;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.PaymentVerifyRequest;
import com.msa.booking.payment.modules.payment.dto.PaymentDtos.WebhookAcknowledgeResponse;
import com.msa.booking.payment.modules.settlement.service.SettlementLifecycleService;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import com.msa.booking.payment.persistence.entity.OrderEntity;
import com.msa.booking.payment.persistence.entity.PaymentAttemptEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.PaymentTransactionEntity;
import com.msa.booking.payment.persistence.entity.OrderItemEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestRepository;
import com.msa.booking.payment.persistence.repository.OrderItemRepository;
import com.msa.booking.payment.persistence.repository.OrderRepository;
import com.msa.booking.payment.persistence.repository.PaymentAttemptRepository;
import com.msa.booking.payment.persistence.repository.PaymentRepository;
import com.msa.booking.payment.persistence.repository.PaymentTransactionRepository;
import com.msa.booking.payment.persistence.repository.ShopOrderSupportRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.json.JSONObject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentLifecycleService {
    private static final String DEFAULT_GATEWAY = "RAZORPAY";

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShopOrderSupportRepository shopOrderSupportRepository;
    private final BookingRepository bookingRepository;
    private final BookingRequestRepository bookingRequestRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RazorpayProperties razorpayProperties;
    private final RazorpaySignatureService razorpaySignatureService;
    private final PaymentWebhookEventService paymentWebhookEventService;
    private final SettlementLifecycleService settlementLifecycleService;
    private final NotificationService notificationService;
    private final BookingPolicyService bookingPolicyService;
    private final ObjectMapper objectMapper;

    public PaymentLifecycleService(
            PaymentRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            ShopOrderSupportRepository shopOrderSupportRepository,
            BookingRepository bookingRepository,
            BookingRequestRepository bookingRequestRepository,
            NamedParameterJdbcTemplate jdbcTemplate,
            RazorpayProperties razorpayProperties,
            RazorpaySignatureService razorpaySignatureService,
            PaymentWebhookEventService paymentWebhookEventService,
            SettlementLifecycleService settlementLifecycleService,
            NotificationService notificationService,
            BookingPolicyService bookingPolicyService,
            ObjectMapper objectMapper
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.shopOrderSupportRepository = shopOrderSupportRepository;
        this.bookingRepository = bookingRepository;
        this.bookingRequestRepository = bookingRequestRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.razorpayProperties = razorpayProperties;
        this.razorpaySignatureService = razorpaySignatureService;
        this.paymentWebhookEventService = paymentWebhookEventService;
        this.settlementLifecycleService = settlementLifecycleService;
        this.notificationService = notificationService;
        this.bookingPolicyService = bookingPolicyService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PaymentStatusResponse status(Long userId, String paymentCode) {
        PaymentEntity payment = getPaymentForUser(userId, paymentCode);
        PaymentAttemptEntity latestAttempt = paymentAttemptRepository.findTopByPaymentIdOrderByIdDesc(payment.getId()).orElse(null);
        PaymentTransactionEntity latestTransaction = paymentTransactionRepository.findTopByPaymentIdOrderByIdDesc(payment.getId()).orElse(null);
        return toStatusResponse(payment, latestAttempt, latestTransaction);
    }

    @Transactional
    public PaymentInitiateResponse initiate(Long userId, String paymentCode, PaymentInitiateRequest request) {
        PaymentEntity payment = getPaymentForUser(userId, paymentCode);
        String gatewayName = normalizeGatewayName(request == null ? null : request.gatewayName());
        if (!DEFAULT_GATEWAY.equals(gatewayName)) {
            throw new BadRequestException("Unsupported payment gateway");
        }
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS) {
            throw new BadRequestException("Payment is already completed");
        }
        ensureRazorpayConfigured();
        preparePayableForRetry(payment);

        PaymentAttemptEntity latestAttempt =
                paymentAttemptRepository.findTopByPaymentIdAndGatewayNameOrderByIdDesc(payment.getId(), gatewayName).orElse(null);
        if (latestAttempt != null
                && latestAttempt.getGatewayOrderId() != null
                && (latestAttempt.getAttemptStatus() == PaymentAttemptStatus.PENDING
                || latestAttempt.getAttemptStatus() == PaymentAttemptStatus.INITIATED)) {
            payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
            paymentRepository.save(payment);
            return toInitiateResponse(payment, latestAttempt);
        }

        String gatewayOrderId = createRazorpayOrder(payment);

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(payment.getId());
        attempt.setGatewayName(gatewayName);
        attempt.setGatewayOrderId(gatewayOrderId);
        attempt.setAttemptStatus(PaymentAttemptStatus.PENDING);
        attempt.setRequestedAmount(payment.getAmount());
        attempt.setAttemptedAt(LocalDateTime.now());
        paymentAttemptRepository.save(attempt);

        payment.setPaymentStatus(PaymentLifecycleStatus.PENDING);
        paymentRepository.save(payment);
        syncFailureOrPendingState(payment, "PENDING");
        notifyPaymentPending(payment);

        return toInitiateResponse(payment, attempt);
    }

    @Transactional
    public PaymentStatusResponse verify(Long userId, String paymentCode, PaymentVerifyRequest request) {
        PaymentEntity payment = getPaymentForUser(userId, paymentCode);
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED) {
            return status(userId, paymentCode);
        }
        PaymentAttemptEntity attempt = paymentAttemptRepository.findTopByGatewayOrderIdOrderByIdDesc(request.gatewayOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found for gateway order"));
        if (!attempt.getPaymentId().equals(payment.getId())) {
            throw new BadRequestException("Gateway order does not belong to the requested payment");
        }

        razorpaySignatureService.verifyPaymentSignature(
                request.gatewayOrderId(),
                request.gatewayPaymentId(),
                request.razorpaySignature()
        );

        markPaymentSuccess(payment, attempt, request.gatewayPaymentId());
        return status(userId, paymentCode);
    }

    @Transactional
    public PaymentStatusResponse failure(Long userId, String paymentCode, PaymentFailureRequest request) {
        PaymentEntity payment = getPaymentForUser(userId, paymentCode);
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED) {
            return status(userId, paymentCode);
        }

        PaymentAttemptEntity attempt = resolveAttemptForFailure(payment, request);
        attempt.setAttemptStatus(PaymentAttemptStatus.FAILED);
        attempt.setResponseCode(buildFailureCode(request));
        paymentAttemptRepository.save(attempt);

        payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        syncFailedState(payment, buildFailureMessage(request));
        notifyPaymentFailed(payment);

        return status(userId, paymentCode);
    }

    @Transactional
    public WebhookAcknowledgeResponse handleRazorpayWebhook(String rawPayload, String signatureHeader, String eventIdHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new BadRequestException("Missing Razorpay signature header");
        }
        razorpaySignatureService.verifyWebhookSignature(rawPayload, signatureHeader);
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String eventType = root.path("event").asText();
            JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
            String gatewayOrderId = paymentEntity.path("order_id").asText(null);
            String gatewayPaymentId = paymentEntity.path("id").asText(null);
            String errorCode = paymentEntity.path("error_code").asText(null);
            String webhookKey = paymentWebhookEventService.resolveWebhookKey(eventIdHeader, rawPayload);

            if (gatewayOrderId == null || gatewayOrderId.isBlank()) {
                return new WebhookAcknowledgeResponse(false, eventType, "Ignored: missing gateway order id");
            }

            PaymentAttemptEntity attempt = paymentAttemptRepository.findTopByGatewayOrderIdOrderByIdDesc(gatewayOrderId).orElse(null);
            if (attempt == null) {
                return new WebhookAcknowledgeResponse(false, eventType, "Ignored: payment attempt not found");
            }

            PaymentEntity payment = paymentRepository.findById(attempt.getPaymentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
            if (!paymentWebhookEventService.registerIfFirst(
                    "MODULES_PAYMENT",
                    webhookKey,
                    eventType,
                    gatewayOrderId,
                    gatewayPaymentId,
                    payment.getId()
            )) {
                return new WebhookAcknowledgeResponse(false, eventType, "Ignored: duplicate webhook event");
            }

            if ("payment.captured".equalsIgnoreCase(eventType) || "payment.authorized".equalsIgnoreCase(eventType)) {
                if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
                    WebhookAcknowledgeResponse response =
                            new WebhookAcknowledgeResponse(false, eventType, "Ignored: missing gateway payment id");
                    paymentWebhookEventService.markProcessed("MODULES_PAYMENT", webhookKey, response.processed(), response.message());
                    return response;
                }
                markPaymentSuccess(payment, attempt, gatewayPaymentId);
                WebhookAcknowledgeResponse response = new WebhookAcknowledgeResponse(true, eventType, "Payment captured");
                paymentWebhookEventService.markProcessed("MODULES_PAYMENT", webhookKey, response.processed(), response.message());
                return response;
            }

            if ("payment.failed".equalsIgnoreCase(eventType)) {
                if (shouldIgnoreLateFailure(payment)) {
                    WebhookAcknowledgeResponse response =
                            new WebhookAcknowledgeResponse(false, eventType, "Ignored: payment already finalized");
                    paymentWebhookEventService.markProcessed("MODULES_PAYMENT", webhookKey, response.processed(), response.message());
                    return response;
                }
                attempt.setAttemptStatus(PaymentAttemptStatus.FAILED);
                attempt.setResponseCode(errorCode);
                paymentAttemptRepository.save(attempt);
                payment.setPaymentStatus(PaymentLifecycleStatus.FAILED);
                payment.setCompletedAt(LocalDateTime.now());
                paymentRepository.save(payment);
                syncFailedState(payment, "Gateway webhook failure");
                notifyPaymentFailed(payment);
                WebhookAcknowledgeResponse response = new WebhookAcknowledgeResponse(true, eventType, "Payment marked failed");
                paymentWebhookEventService.markProcessed("MODULES_PAYMENT", webhookKey, response.processed(), response.message());
                return response;
            }

            WebhookAcknowledgeResponse response = new WebhookAcknowledgeResponse(false, eventType, "Ignored: unsupported event");
            paymentWebhookEventService.markProcessed("MODULES_PAYMENT", webhookKey, response.processed(), response.message());
            return response;
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("Unable to process webhook payload");
        }
    }

    private void markPaymentSuccess(PaymentEntity payment, PaymentAttemptEntity attempt, String gatewayPaymentId) {
        if (shouldIgnoreLateSuccess(payment)) {
            return;
        }
        if (!paymentTransactionRepository.existsByGatewayTransactionId(gatewayPaymentId)) {
            PaymentTransactionEntity transaction = new PaymentTransactionEntity();
            transaction.setPaymentId(payment.getId());
            transaction.setGatewayTransactionId(gatewayPaymentId);
            transaction.setTransactionType(PaymentTransactionType.PAYMENT);
            transaction.setTransactionStatus(PaymentTransactionStatus.SUCCESS);
            transaction.setAmount(payment.getAmount());
            transaction.setProcessedAt(LocalDateTime.now());
            paymentTransactionRepository.save(transaction);
        }

        attempt.setAttemptStatus(PaymentAttemptStatus.SUCCESS);
        attempt.setResponseCode("VERIFIED");
        paymentAttemptRepository.save(attempt);

        payment.setPaymentStatus(PaymentLifecycleStatus.SUCCESS);
        payment.setCompletedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        syncSuccessState(payment);
        settlementLifecycleService.recordSuccessfulPayment(payment);
        notifyPaymentSuccess(payment);
    }

    private void notifyPaymentPending(PaymentEntity payment) {
        if (payment.getPayableType() == PayableType.ORDER) {
            OrderEntity order = orderRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked order not found"));
            notificationService.notifyUser(
                    payment.getPayerUserId(),
                    "SHOP_ORDER_PAYMENT_PENDING",
                    "Complete your payment",
                    "Your shop order payment is waiting for completion.",
                    Map.of(
                            "orderId", order.getId(),
                            "orderCode", order.getOrderCode(),
                            "paymentCode", payment.getPaymentCode()
                    )
            );
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            notificationService.notifyUser(
                    payment.getPayerUserId(),
                    "BOOKING_PAYMENT_PENDING",
                    "Complete your payment",
                    "Your booking payment is waiting for completion.",
                    Map.of(
                            "bookingId", booking.getId(),
                            "bookingCode", booking.getBookingCode(),
                            "paymentCode", payment.getPaymentCode()
                    )
            );
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING_REQUEST) {
            BookingRequestEntity request = bookingRequestRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking request not found"));
            notificationService.notifyUser(
                    payment.getPayerUserId(),
                    "BOOKING_PAYMENT_PENDING",
                    "Complete your payment",
                    "Your group labour booking payment is waiting for completion.",
                    Map.of(
                            "requestId", request.getId(),
                            "requestCode", request.getRequestCode(),
                            "paymentCode", payment.getPaymentCode()
                    )
            );
        }
    }

    private void notifyPaymentSuccess(PaymentEntity payment) {
        if (payment.getPayableType() == PayableType.ORDER) {
            OrderEntity order = orderRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked order not found"));
            notificationService.notifyUser(
                    payment.getPayerUserId(),
                    "SHOP_ORDER_PAYMENT_SUCCESS",
                    "Payment successful",
                    "Your shop order payment was completed successfully.",
                    Map.of(
                            "orderId", order.getId(),
                            "orderCode", order.getOrderCode(),
                            "paymentCode", payment.getPaymentCode()
                    )
            );
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            notificationService.notifyUser(
                    payment.getPayerUserId(),
                    "BOOKING_PAYMENT_SUCCESS",
                    "Payment successful",
                    "Your booking payment was completed successfully.",
                    Map.of(
                            "bookingId", booking.getId(),
                            "bookingCode", booking.getBookingCode(),
                            "paymentCode", payment.getPaymentCode()
                    )
            );
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING_REQUEST) {
            BookingRequestEntity request = bookingRequestRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking request not found"));
            notificationService.notifyUser(
                    payment.getPayerUserId(),
                    "BOOKING_PAYMENT_SUCCESS",
                    "Payment successful",
                    "Your group labour booking payment was completed successfully.",
                    Map.of(
                            "requestId", request.getId(),
                            "requestCode", request.getRequestCode(),
                            "paymentCode", payment.getPaymentCode()
                    )
            );
        }
    }

    private void notifyPaymentFailed(PaymentEntity payment) {
        if (payment.getPayableType() == PayableType.ORDER) {
            OrderEntity order = orderRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked order not found"));
            notificationService.notifyUser(
                    payment.getPayerUserId(),
                    "SHOP_ORDER_PAYMENT_FAILED",
                    "Payment not completed",
                    "Your shop order payment could not be completed.",
                    Map.of(
                            "orderId", order.getId(),
                            "orderCode", order.getOrderCode(),
                            "paymentCode", payment.getPaymentCode()
                    )
            );
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            notificationService.notifyUser(
                    payment.getPayerUserId(),
                    "BOOKING_PAYMENT_FAILED",
                    "Payment not completed",
                    "Your booking payment could not be completed.",
                    Map.of(
                            "bookingId", booking.getId(),
                            "bookingCode", booking.getBookingCode(),
                            "paymentCode", payment.getPaymentCode()
                    )
            );
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING_REQUEST) {
            BookingRequestEntity request = bookingRequestRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking request not found"));
            notificationService.notifyUser(
                    payment.getPayerUserId(),
                    "BOOKING_PAYMENT_FAILED",
                    "Payment not completed",
                    "Your group labour booking payment could not be completed.",
                    Map.of(
                            "requestId", request.getId(),
                            "requestCode", request.getRequestCode(),
                            "paymentCode", payment.getPaymentCode()
                    )
            );
        }
    }

    private void syncSuccessState(PaymentEntity payment) {
        if (payment.getPayableType() == PayableType.ORDER) {
            OrderEntity order = orderRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked order not found"));
            OrderLifecycleStatus oldStatus = order.getOrderStatus();
            order.setPaymentStatus(PayablePaymentStatus.PAID);
            if (oldStatus == OrderLifecycleStatus.CREATED
                    || oldStatus == OrderLifecycleStatus.ACCEPTED
                    || oldStatus == OrderLifecycleStatus.PAYMENT_PENDING) {
                order.setOrderStatus(OrderLifecycleStatus.PAYMENT_COMPLETED);
                insertOrderStatusHistory(order.getId(), oldStatus.name(), OrderLifecycleStatus.PAYMENT_COMPLETED.name(), payment.getPayerUserId(), "Payment completed");
            }
            orderRepository.save(order);
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            BookingLifecycleStatus oldStatus = booking.getBookingStatus();
            booking.setPaymentStatus(PayablePaymentStatus.PAID);
            if (oldStatus == BookingLifecycleStatus.CREATED
                    || oldStatus == BookingLifecycleStatus.ACCEPTED
                    || oldStatus == BookingLifecycleStatus.PAYMENT_PENDING) {
                booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_COMPLETED);
                insertBookingStatusHistory(booking.getId(), oldStatus.name(), BookingLifecycleStatus.PAYMENT_COMPLETED.name(), payment.getPayerUserId(), "Payment completed");
            }
            bookingRepository.save(booking);
            prepareStartWorkOtp(booking);
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING_REQUEST) {
            for (BookingEntity booking : requestBookings(payment.getPayableId())) {
                BookingLifecycleStatus oldStatus = booking.getBookingStatus();
                booking.setPaymentStatus(PayablePaymentStatus.PAID);
                if (oldStatus == BookingLifecycleStatus.CREATED
                        || oldStatus == BookingLifecycleStatus.ACCEPTED
                        || oldStatus == BookingLifecycleStatus.PAYMENT_PENDING) {
                    booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_COMPLETED);
                    insertBookingStatusHistory(booking.getId(), oldStatus.name(), BookingLifecycleStatus.PAYMENT_COMPLETED.name(), payment.getPayerUserId(), "Group booking payment completed");
                }
                bookingRepository.save(booking);
                prepareStartWorkOtp(booking);
            }
        }
    }

    private void prepareStartWorkOtp(BookingEntity booking) {
        if (booking == null || booking.getId() == null || booking.getUserId() == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE booking_action_otps
                   SET otp_status = 'CANCELLED'
                 WHERE booking_id = :bookingId
                   AND otp_purpose = 'START_WORK'
                   AND otp_status = 'GENERATED'
                """, new MapSqlParameterSource("bookingId", booking.getId()));

        LocalDateTime expiryBase = booking.getScheduledStartAt() == null ? LocalDateTime.now() : booking.getScheduledStartAt();
        int reachMinutes = bookingPolicyService == null ? 45 : bookingPolicyService.noShowAutoCancelMinutes();
        jdbcTemplate.update("""
                INSERT INTO booking_action_otps
                    (booking_id, otp_purpose, otp_code, issued_to_user_id, otp_status, expires_at)
                VALUES
                    (:bookingId, 'START_WORK', :otpCode, :issuedToUserId, 'GENERATED', :expiresAt)
                """, new MapSqlParameterSource()
                .addValue("bookingId", booking.getId())
                .addValue("otpCode", String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1_000_000)))
                .addValue("issuedToUserId", booking.getUserId())
                .addValue("expiresAt", expiryBase.plusMinutes(reachMinutes)));
    }

    private void preparePayableForRetry(PaymentEntity payment) {
        if (payment.getPayableType() != PayableType.ORDER
                || payment.getPaymentStatus() != PaymentLifecycleStatus.FAILED) {
            return;
        }

        OrderEntity order = orderRepository.findById(payment.getPayableId())
                .orElseThrow(() -> new ResourceNotFoundException("Linked order not found"));
        if (order.getPaymentStatus() == PayablePaymentStatus.PAID
                || order.getPaymentStatus() == PayablePaymentStatus.REFUNDED) {
            return;
        }

        if (order.getOrderStatus() == OrderLifecycleStatus.CANCELLED) {
            reReserveOrderInventory(order);
            insertOrderStatusHistory(
                    order.getId(),
                    OrderLifecycleStatus.CANCELLED.name(),
                    OrderLifecycleStatus.PAYMENT_PENDING.name(),
                    payment.getPayerUserId(),
                    "Payment retry initiated"
            );
        }

        order.setPaymentStatus(PayablePaymentStatus.PENDING);
        order.setOrderStatus(OrderLifecycleStatus.PAYMENT_PENDING);
        orderRepository.save(order);

        payment.setPaymentStatus(PaymentLifecycleStatus.INITIATED);
        payment.setCompletedAt(null);
        paymentRepository.save(payment);
    }

    private void reReserveOrderInventory(OrderEntity order) {
        for (OrderItemEntity orderItem : orderItemRepository.findByOrderId(order.getId())) {
            int reserved = shopOrderSupportRepository.reserveInventory(orderItem.getVariantId(), orderItem.getQuantity());
            if (reserved == 0) {
                throw new BadRequestException(
                        "One or more items are no longer available to retry payment for order " + order.getOrderCode() + "."
                );
            }
        }
    }

    private void syncFailureOrPendingState(PaymentEntity payment, String targetPaymentStatus) {
        if (payment.getPayableType() == PayableType.ORDER) {
            OrderEntity order = orderRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked order not found"));
            order.setPaymentStatus("FAILED".equalsIgnoreCase(targetPaymentStatus) ? PayablePaymentStatus.FAILED : PayablePaymentStatus.PENDING);
            if (order.getOrderStatus() == OrderLifecycleStatus.CREATED || order.getOrderStatus() == OrderLifecycleStatus.ACCEPTED) {
                order.setOrderStatus(OrderLifecycleStatus.PAYMENT_PENDING);
            }
            orderRepository.save(order);
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            booking.setPaymentStatus("FAILED".equalsIgnoreCase(targetPaymentStatus) ? PayablePaymentStatus.FAILED : PayablePaymentStatus.PENDING);
            if (booking.getBookingStatus() == BookingLifecycleStatus.CREATED || booking.getBookingStatus() == BookingLifecycleStatus.ACCEPTED) {
                booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_PENDING);
            }
            bookingRepository.save(booking);
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING_REQUEST) {
            List<BookingEntity> bookings = requestBookings(payment.getPayableId());
            for (BookingEntity booking : bookings) {
                booking.setPaymentStatus("FAILED".equalsIgnoreCase(targetPaymentStatus) ? PayablePaymentStatus.FAILED : PayablePaymentStatus.PENDING);
                if (booking.getBookingStatus() == BookingLifecycleStatus.CREATED || booking.getBookingStatus() == BookingLifecycleStatus.ACCEPTED) {
                    booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_PENDING);
                }
            }
            bookingRepository.saveAll(bookings);
        }
    }

    private void syncFailedState(PaymentEntity payment, String reason) {
        if (payment.getPayableType() == PayableType.ORDER) {
            OrderEntity order = orderRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked order not found"));
            if (order.getPaymentStatus() == PayablePaymentStatus.PAID || order.getPaymentStatus() == PayablePaymentStatus.REFUNDED) {
                return;
            }
            OrderLifecycleStatus oldStatus = order.getOrderStatus();
            order.setPaymentStatus(PayablePaymentStatus.FAILED);
            if (oldStatus != OrderLifecycleStatus.CANCELLED) {
                order.setOrderStatus(OrderLifecycleStatus.CANCELLED);
                insertOrderStatusHistory(order.getId(), oldStatus.name(), OrderLifecycleStatus.CANCELLED.name(), payment.getPayerUserId(), reason);
            }
            orderRepository.save(order);
            releaseOrderInventory(order.getId());
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            if (booking.getPaymentStatus() == PayablePaymentStatus.PAID || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED) {
                return;
            }
            BookingLifecycleStatus oldStatus = booking.getBookingStatus();
            booking.setPaymentStatus(PayablePaymentStatus.FAILED);
            if (oldStatus != BookingLifecycleStatus.CANCELLED) {
                booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
                insertBookingStatusHistory(booking.getId(), oldStatus.name(), BookingLifecycleStatus.CANCELLED.name(), payment.getPayerUserId(), reason);
            }
            bookingRepository.save(booking);
            return;
        }

        if (payment.getPayableType() == PayableType.BOOKING_REQUEST) {
            List<BookingEntity> bookings = requestBookings(payment.getPayableId());
            for (BookingEntity booking : bookings) {
                if (booking.getPaymentStatus() == PayablePaymentStatus.PAID || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED) {
                    continue;
                }
                BookingLifecycleStatus oldStatus = booking.getBookingStatus();
                booking.setPaymentStatus(PayablePaymentStatus.FAILED);
                if (oldStatus != BookingLifecycleStatus.CANCELLED) {
                    booking.setBookingStatus(BookingLifecycleStatus.CANCELLED);
                    insertBookingStatusHistory(booking.getId(), oldStatus.name(), BookingLifecycleStatus.CANCELLED.name(), payment.getPayerUserId(), reason);
                }
            }
            bookingRepository.saveAll(bookings);
        }
    }

    private void insertOrderStatusHistory(Long orderId, String oldStatus, String newStatus, Long userId, String reason) {
        jdbcTemplate.update("""
                INSERT INTO order_status_history (
                    order_id,
                    old_status,
                    new_status,
                    changed_by_user_id,
                    reason,
                    changed_at
                ) VALUES (
                    :orderId,
                    :oldStatus,
                    :newStatus,
                    :userId,
                    :reason,
                    :changedAt
                )
                """, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("oldStatus", oldStatus)
                .addValue("newStatus", newStatus)
                .addValue("userId", userId)
                .addValue("reason", reason)
                .addValue("changedAt", LocalDateTime.now()));
    }

    private void insertBookingStatusHistory(Long bookingId, String oldStatus, String newStatus, Long userId, String reason) {
        jdbcTemplate.update("""
                INSERT INTO booking_status_history (
                    booking_id,
                    old_status,
                    new_status,
                    changed_by_user_id,
                    reason,
                    changed_at
                ) VALUES (
                    :bookingId,
                    :oldStatus,
                    :newStatus,
                    :userId,
                    :reason,
                    :changedAt
                )
                """, new MapSqlParameterSource()
                .addValue("bookingId", bookingId)
                .addValue("oldStatus", oldStatus)
                .addValue("newStatus", newStatus)
                .addValue("userId", userId)
                .addValue("reason", reason)
                .addValue("changedAt", LocalDateTime.now()));
    }

    private PaymentEntity getPayment(String paymentCode) {
        return paymentRepository.findByPaymentCode(paymentCode)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    private PaymentEntity getPaymentForUser(Long userId, String paymentCode) {
        PaymentEntity payment = getPayment(paymentCode);
        if (!userId.equals(payment.getPayerUserId())) {
            throw new ResourceNotFoundException("Payment not found");
        }
        return payment;
    }

    private List<BookingEntity> requestBookings(Long bookingRequestId) {
        return bookingRepository.findByBookingRequestIdOrderByIdAsc(bookingRequestId);
    }

    private PaymentStatusResponse toStatusResponse(
            PaymentEntity payment,
            PaymentAttemptEntity latestAttempt,
            PaymentTransactionEntity latestTransaction
    ) {
        return new PaymentStatusResponse(
                payment.getId(),
                payment.getPaymentCode(),
                payment.getPayableType().name(),
                payment.getPayableId(),
                payment.getPaymentStatus().name(),
                payment.getAmount(),
                payment.getCurrencyCode(),
                latestAttempt == null ? null : latestAttempt.getGatewayName(),
                latestAttempt == null ? null : latestAttempt.getGatewayOrderId(),
                latestAttempt == null ? null : latestAttempt.getAttemptStatus().name(),
                latestTransaction == null ? null : latestTransaction.getGatewayTransactionId(),
                payment.getInitiatedAt(),
                payment.getCompletedAt()
        );
    }

    private PaymentInitiateResponse toInitiateResponse(PaymentEntity payment, PaymentAttemptEntity attempt) {
        return new PaymentInitiateResponse(
                payment.getId(),
                payment.getPaymentCode(),
                attempt.getGatewayName(),
                attempt.getGatewayOrderId(),
                razorpayProperties.getKeyId(),
                payment.getAmount(),
                payment.getCurrencyCode(),
                payment.getPaymentStatus().name(),
                payment.getPayableType().name(),
                payment.getPayableId()
        );
    }

    private String normalizeGatewayName(String requestedGateway) {
        if (requestedGateway == null || requestedGateway.isBlank()) {
            return DEFAULT_GATEWAY;
        }
        return requestedGateway.trim().toUpperCase();
    }

    private void ensureRazorpayConfigured() {
        if (!razorpayProperties.isEnabled()
                || razorpayProperties.getKeyId() == null || razorpayProperties.getKeyId().isBlank()
                || razorpayProperties.getKeySecret() == null || razorpayProperties.getKeySecret().isBlank()) {
            throw new BadRequestException("Razorpay is not configured for this environment");
        }
    }

    protected String createRazorpayOrder(PaymentEntity payment) {
        try {
            RazorpayClient client = new RazorpayClient(razorpayProperties.getKeyId(), razorpayProperties.getKeySecret());
            JSONObject options = new JSONObject();
            options.put("amount", payment.getAmount().multiply(BigDecimal.valueOf(100)).longValueExact());
            options.put("currency", payment.getCurrencyCode());
            options.put("receipt", payment.getPaymentCode());
            options.put("notes", new JSONObject(Map.of(
                    "paymentCode", payment.getPaymentCode(),
                    "payableType", payment.getPayableType(),
                    "payableId", String.valueOf(payment.getPayableId())
            )));
            Order order = client.orders.create(options);
            return order.get("id");
        } catch (ArithmeticException exception) {
            throw new BadRequestException("Payment amount is invalid for gateway order creation");
        } catch (Exception exception) {
            throw new BadRequestException("Unable to create Razorpay order");
        }
    }

    private PaymentAttemptEntity resolveAttemptForFailure(PaymentEntity payment, PaymentFailureRequest request) {
        if (request != null && request.gatewayOrderId() != null && !request.gatewayOrderId().isBlank()) {
            PaymentAttemptEntity attempt = paymentAttemptRepository.findTopByGatewayOrderIdOrderByIdDesc(request.gatewayOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found for gateway order"));
            if (!attempt.getPaymentId().equals(payment.getId())) {
                throw new BadRequestException("Gateway order does not belong to the requested payment");
            }
            return attempt;
        }
        return paymentAttemptRepository.findTopByPaymentIdOrderByIdDesc(payment.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found"));
    }

    private String buildFailureCode(PaymentFailureRequest request) {
        if (request == null) {
            return "FAILED";
        }
        if (request.failureCode() != null && !request.failureCode().isBlank()) {
            return request.failureCode().trim();
        }
        if (request.failureMessage() != null && !request.failureMessage().isBlank()) {
            return request.failureMessage().trim();
        }
        return "FAILED";
    }

    private String buildFailureMessage(PaymentFailureRequest request) {
        if (request == null) {
            return "Payment failed";
        }
        if (request.failureMessage() != null && !request.failureMessage().isBlank()) {
            return request.failureMessage().trim();
        }
        if (request.failureCode() != null && !request.failureCode().isBlank()) {
            return "Payment failed: " + request.failureCode().trim();
        }
        return "Payment failed";
    }

    private void releaseOrderInventory(Long orderId) {
        for (OrderItemEntity item : orderItemRepository.findByOrderId(orderId)) {
            jdbcTemplate.update("""
                    UPDATE inventory
                    SET reserved_quantity = GREATEST(COALESCE(reserved_quantity, 0) - :quantity, 0),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE variant_id = :variantId
                    """, new MapSqlParameterSource()
                    .addValue("quantity", item.getQuantity())
                    .addValue("variantId", item.getVariantId()));
        }
    }

    private boolean shouldIgnoreLateSuccess(PaymentEntity payment) {
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.FAILED
                || payment.getPaymentStatus() == PaymentLifecycleStatus.CANCELLED
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED) {
            return true;
        }
        if (payment.getPayableType() == PayableType.ORDER) {
            OrderEntity order = orderRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked order not found"));
            return order.getOrderStatus() == OrderLifecycleStatus.CANCELLED
                    || order.getPaymentStatus() == PayablePaymentStatus.FAILED
                    || order.getPaymentStatus() == PayablePaymentStatus.REFUNDED;
        }
        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            return booking.getBookingStatus() == BookingLifecycleStatus.CANCELLED
                    || booking.getPaymentStatus() == PayablePaymentStatus.FAILED
                    || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED;
        }
        if (payment.getPayableType() == PayableType.BOOKING_REQUEST) {
            return requestBookings(payment.getPayableId()).stream().allMatch(booking ->
                    booking.getBookingStatus() == BookingLifecycleStatus.CANCELLED
                            || booking.getPaymentStatus() == PayablePaymentStatus.FAILED
                            || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED);
        }
        return false;
    }

    private boolean shouldIgnoreLateFailure(PaymentEntity payment) {
        if (payment.getPaymentStatus() == PaymentLifecycleStatus.SUCCESS
                || payment.getPaymentStatus() == PaymentLifecycleStatus.REFUNDED) {
            return true;
        }
        if (payment.getPayableType() == PayableType.ORDER) {
            OrderEntity order = orderRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked order not found"));
            return order.getPaymentStatus() == PayablePaymentStatus.PAID
                    || order.getPaymentStatus() == PayablePaymentStatus.REFUNDED;
        }
        if (payment.getPayableType() == PayableType.BOOKING) {
            BookingEntity booking = bookingRepository.findById(payment.getPayableId())
                    .orElseThrow(() -> new ResourceNotFoundException("Linked booking not found"));
            return booking.getPaymentStatus() == PayablePaymentStatus.PAID
                    || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED;
        }
        if (payment.getPayableType() == PayableType.BOOKING_REQUEST) {
            return requestBookings(payment.getPayableId()).stream().allMatch(booking ->
                    booking.getPaymentStatus() == PayablePaymentStatus.PAID
                            || booking.getPaymentStatus() == PayablePaymentStatus.REFUNDED);
        }
        return false;
    }
}
