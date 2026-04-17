package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.*;
import com.msa.booking.payment.booking.support.BookingPolicyService;
import com.msa.booking.payment.booking.service.BookingCandidateMatchingService;
import com.msa.booking.payment.booking.service.BookingRequestService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.BookingRequestCandidateStatus;
import com.msa.booking.payment.domain.enums.BookingRequestMode;
import com.msa.booking.payment.domain.enums.BookingRequestStatus;
import com.msa.booking.payment.persistence.entity.BookingRequestCandidateEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import com.msa.booking.payment.persistence.repository.BookingRequestCandidateRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import com.msa.booking.payment.notification.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingRequestServiceImpl implements BookingRequestService {
    private static final int DEFAULT_DIRECT_TIMEOUT_SECONDS = 30;

    private final BookingRequestRepository bookingRequestRepository;
    private final BookingRequestCandidateRepository bookingRequestCandidateRepository;
    private final BookingCandidateMatchingService bookingCandidateMatchingService;
    private final BookingPolicyService bookingPolicyService;
    private final BookingSupportRepository bookingSupportRepository;
    private final NotificationService notificationService;

    public BookingRequestServiceImpl(
            BookingRequestRepository bookingRequestRepository,
            BookingRequestCandidateRepository bookingRequestCandidateRepository,
            BookingCandidateMatchingService bookingCandidateMatchingService,
            BookingPolicyService bookingPolicyService,
            BookingSupportRepository bookingSupportRepository,
            NotificationService notificationService
    ) {
        this.bookingRequestRepository = bookingRequestRepository;
        this.bookingRequestCandidateRepository = bookingRequestCandidateRepository;
        this.bookingCandidateMatchingService = bookingCandidateMatchingService;
        this.bookingPolicyService = bookingPolicyService;
        this.bookingSupportRepository = bookingSupportRepository;
        this.notificationService = notificationService;
    }

    @Override
    @Transactional
    public BookingRequestData createRequest(CreateBookingRequest request) {
        validateRequest(request);
        int timeoutSeconds = resolveTimeoutSeconds(request.bookingType());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(timeoutSeconds);

        BookingRequestEntity entity = new BookingRequestEntity();
        entity.setRequestCode(generateRequestCode());
        entity.setBookingType(request.bookingType());
        entity.setRequestMode(request.requestMode());
        entity.setRequestStatus(BookingRequestStatus.OPEN);
        entity.setUserId(request.userId());
        entity.setAddressId(request.addressId());
        entity.setTargetProviderEntityType(request.targetProviderEntityType());
        entity.setTargetProviderEntityId(request.targetProviderEntityId());
        entity.setCategoryId(request.categoryId());
        entity.setSubcategoryId(request.subcategoryId());
        entity.setScheduledStartAt(request.scheduledStartAt());
        entity.setExpiresAt(expiresAt);
        entity.setPriceMinAmount(request.priceMinAmount());
        entity.setPriceMaxAmount(request.priceMaxAmount());
        entity.setSearchLatitude(request.searchLatitude());
        entity.setSearchLongitude(request.searchLongitude());
        BookingRequestEntity savedRequest = bookingRequestRepository.save(entity);

        List<BookingRequestCandidateEntity> candidateEntities = buildCandidates(savedRequest.getId(), request, now, expiresAt);
        List<BookingRequestCandidateEntity> savedCandidates = bookingRequestCandidateRepository.saveAll(candidateEntities);
        notifyCandidateProviders(savedRequest, savedCandidates);
        notificationService.notifyUser(
                request.userId(),
                "BOOKING_REQUEST_CREATED",
                "Booking request created",
                "We have started finding providers for your booking request.",
                java.util.Map.of("requestId", savedRequest.getId(), "requestCode", savedRequest.getRequestCode())
        );

        return toData(savedRequest, savedCandidates, timeoutSeconds);
    }

    @Override
    @Transactional
    public ExpireBookingRequestsResponse expireTimedOutRequests() {
        LocalDateTime now = LocalDateTime.now();
        List<BookingRequestEntity> openRequests = bookingRequestRepository
                .findByRequestStatusAndExpiresAtBefore(BookingRequestStatus.OPEN, now);
        int expiredCandidates = 0;
        for (BookingRequestEntity request : openRequests) {
            request.setRequestStatus(BookingRequestStatus.EXPIRED);
            List<BookingRequestCandidateEntity> candidates = bookingRequestCandidateRepository
                    .findByRequestIdAndCandidateStatus(request.getId(), BookingRequestCandidateStatus.PENDING);
            for (BookingRequestCandidateEntity candidate : candidates) {
                candidate.setCandidateStatus(BookingRequestCandidateStatus.EXPIRED);
                candidate.setRespondedAt(now);
                expiredCandidates++;
            }
            bookingRequestCandidateRepository.saveAll(candidates);
            notificationService.notifyUser(
                    request.getUserId(),
                    "BOOKING_REQUEST_EXPIRED",
                    "Booking request expired",
                    "Your booking request expired because no provider accepted it in time.",
                    java.util.Map.of("requestId", request.getId(), "requestCode", request.getRequestCode())
            );
        }
        bookingRequestRepository.saveAll(openRequests);
        return new ExpireBookingRequestsResponse(openRequests.size(), expiredCandidates);
    }

    private void validateRequest(CreateBookingRequest request) {
        if (request.requestMode() == BookingRequestMode.DIRECT) {
            if (request.targetProviderEntityType() == null || request.targetProviderEntityId() == null) {
                throw new BadRequestException("Direct booking request must include the target provider.");
            }
            if (request.searchLatitude() == null || request.searchLongitude() == null) {
                throw new BadRequestException("Direct booking request requires user live location.");
            }
            return;
        }
        if (request.candidates() == null || request.candidates().isEmpty()) {
            if (request.bookingType() == BookingFlowType.LABOUR && request.categoryId() == null) {
                throw new BadRequestException("Labour broadcast booking request requires category id.");
            }
            if (request.bookingType() == BookingFlowType.SERVICE
                    && request.categoryId() == null
                    && request.subcategoryId() == null) {
                throw new BadRequestException("Service broadcast booking request requires category or subcategory.");
            }
        }
    }

    private int resolveTimeoutSeconds(BookingFlowType bookingType) {
        if (bookingType == BookingFlowType.LABOUR) {
            return bookingPolicyService.labourDirectRequestTimeoutSeconds();
        }
        if (bookingType == BookingFlowType.SERVICE) {
            return bookingPolicyService.serviceDirectRequestTimeoutSeconds();
        }
        return DEFAULT_DIRECT_TIMEOUT_SECONDS;
    }

    private List<BookingRequestCandidateEntity> buildCandidates(
            Long requestId,
            CreateBookingRequest request,
            LocalDateTime now,
            LocalDateTime expiresAt
    ) {
        List<BookingRequestCandidateEntity> candidates = new ArrayList<>();
        if (request.requestMode() == BookingRequestMode.DIRECT) {
            BookingRequestCandidateInput directCandidate = bookingCandidateMatchingService.resolveDirectCandidate(request);
            BookingRequestCandidateEntity entity = new BookingRequestCandidateEntity();
            entity.setRequestId(requestId);
            entity.setProviderEntityType(directCandidate.providerEntityType());
            entity.setProviderEntityId(directCandidate.providerEntityId());
            entity.setCandidateStatus(BookingRequestCandidateStatus.PENDING);
            entity.setQuotedPriceAmount(directCandidate.quotedPriceAmount());
            entity.setDistanceKm(directCandidate.distanceKm());
            entity.setNotifiedAt(now);
            entity.setExpiresAt(expiresAt);
            candidates.add(entity);
            return candidates;
        }

        List<BookingRequestCandidateInput> resolvedInputs = request.candidates();
        if (resolvedInputs == null || resolvedInputs.isEmpty()) {
            resolvedInputs = bookingCandidateMatchingService.resolveBroadcastCandidates(request);
        }

        for (BookingRequestCandidateInput input : resolvedInputs) {
            BookingRequestCandidateEntity entity = new BookingRequestCandidateEntity();
            entity.setRequestId(requestId);
            entity.setProviderEntityType(input.providerEntityType());
            entity.setProviderEntityId(input.providerEntityId());
            entity.setCandidateStatus(BookingRequestCandidateStatus.PENDING);
            entity.setQuotedPriceAmount(input.quotedPriceAmount());
            entity.setDistanceKm(input.distanceKm());
            entity.setNotifiedAt(now);
            entity.setExpiresAt(expiresAt);
            candidates.add(entity);
        }
        return candidates;
    }

    private BookingRequestData toData(
            BookingRequestEntity request,
            List<BookingRequestCandidateEntity> candidates,
            int timeoutSeconds
    ) {
        List<BookingRequestCandidateData> candidateData = candidates.stream()
                .sorted(Comparator.comparing(BookingRequestCandidateEntity::getId))
                .map(candidate -> new BookingRequestCandidateData(
                        candidate.getId(),
                        candidate.getProviderEntityType(),
                        candidate.getProviderEntityId(),
                        candidate.getCandidateStatus(),
                        candidate.getQuotedPriceAmount(),
                        candidate.getDistanceKm(),
                        candidate.getExpiresAt()
                ))
                .collect(Collectors.toList());
        return new BookingRequestData(
                request.getId(),
                request.getRequestCode(),
                request.getBookingType(),
                request.getRequestMode(),
                request.getRequestStatus(),
                request.getUserId(),
                request.getAddressId(),
                request.getScheduledStartAt(),
                request.getExpiresAt(),
                timeoutSeconds,
                candidateData
        );
    }

    private String generateRequestCode() {
        return "BRQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private void notifyCandidateProviders(BookingRequestEntity request, List<BookingRequestCandidateEntity> candidates) {
        for (BookingRequestCandidateEntity candidate : candidates) {
            Long providerUserId = candidate.getProviderEntityType() == com.msa.booking.payment.domain.enums.ProviderEntityType.LABOUR
                    ? bookingSupportRepository.findLabourUserId(candidate.getProviderEntityId()).orElse(null)
                    : bookingSupportRepository.findServiceProviderUserId(candidate.getProviderEntityId()).orElse(null);
            if (providerUserId == null) {
                continue;
            }
            notificationService.notifyUser(
                    providerUserId,
                    "BOOKING_REQUEST_NEW",
                    "New booking request",
                    "A customer is waiting for your response. Accept the request if you are available.",
                    java.util.Map.of(
                            "requestId", request.getId(),
                            "requestCode", request.getRequestCode(),
                            "candidateId", candidate.getId(),
                            "providerEntityType", candidate.getProviderEntityType().name(),
                            "providerEntityId", candidate.getProviderEntityId()
                    )
            );
        }
    }
}
