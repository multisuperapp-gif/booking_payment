package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.*;
import com.msa.booking.payment.booking.service.BookingDecisionService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.*;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestCandidateEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import com.msa.booking.payment.persistence.repository.BookingRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestCandidateRepository;
import com.msa.booking.payment.persistence.repository.BookingRequestRepository;
import com.msa.booking.payment.persistence.repository.BookingSupportRepository;
import com.msa.booking.payment.notification.service.NotificationService;
import com.msa.booking.payment.booking.support.BookingHistoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class BookingDecisionServiceImpl implements BookingDecisionService {
    private final BookingRequestRepository bookingRequestRepository;
    private final BookingRequestCandidateRepository bookingRequestCandidateRepository;
    private final BookingRepository bookingRepository;
    private final BookingSupportRepository bookingSupportRepository;
    private final BookingHistoryService bookingHistoryService;
    private final NotificationService notificationService;

    public BookingDecisionServiceImpl(
            BookingRequestRepository bookingRequestRepository,
            BookingRequestCandidateRepository bookingRequestCandidateRepository,
            BookingRepository bookingRepository,
            BookingSupportRepository bookingSupportRepository,
            BookingHistoryService bookingHistoryService,
            NotificationService notificationService
    ) {
        this.bookingRequestRepository = bookingRequestRepository;
        this.bookingRequestCandidateRepository = bookingRequestCandidateRepository;
        this.bookingRepository = bookingRepository;
        this.bookingSupportRepository = bookingSupportRepository;
        this.bookingHistoryService = bookingHistoryService;
        this.notificationService = notificationService;
    }

    @Override
    @Transactional
    public BookingAcceptanceData acceptCandidate(Long actingUserId, AcceptBookingCandidateRequest request) {
        BookingRequestEntity bookingRequest = bookingRequestRepository.findByIdAndRequestStatus(
                        request.requestId(), BookingRequestStatus.OPEN)
                .orElseThrow(() -> new BadRequestException("Open booking request not found."));

        BookingRequestCandidateEntity acceptedCandidate = bookingRequestCandidateRepository
                .findByIdAndRequestId(request.candidateId(), request.requestId())
                .orElseThrow(() -> new BadRequestException("Booking request candidate not found."));
        if (acceptedCandidate.getCandidateStatus() != BookingRequestCandidateStatus.PENDING) {
            throw new BadRequestException("Only pending candidates can accept a booking request.");
        }
        validateCandidateOwner(actingUserId, acceptedCandidate);

        LocalDateTime now = LocalDateTime.now();
        acceptedCandidate.setCandidateStatus(BookingRequestCandidateStatus.ACCEPTED);
        acceptedCandidate.setRespondedAt(now);

        List<BookingRequestCandidateEntity> allCandidates = bookingRequestCandidateRepository.findByRequestId(request.requestId());
        int closedCount = 0;
        for (BookingRequestCandidateEntity candidate : allCandidates) {
            if (candidate.getId().equals(acceptedCandidate.getId())) {
                continue;
            }
            if (candidate.getCandidateStatus() == BookingRequestCandidateStatus.PENDING) {
                candidate.setCandidateStatus(BookingRequestCandidateStatus.CLOSED);
                candidate.setRespondedAt(now);
                closedCount++;
            }
        }
        bookingRequestCandidateRepository.saveAll(allCandidates);
        bookingRequestCandidateRepository.save(acceptedCandidate);

        bookingRequest.setRequestStatus(BookingRequestStatus.CONVERTED_TO_BOOKING);
        bookingRequestRepository.save(bookingRequest);

        if (acceptedCandidate.getProviderEntityType() == ProviderEntityType.SERVICE_PROVIDER) {
            int updated = bookingSupportRepository.decrementAvailableServiceMen(acceptedCandidate.getProviderEntityId());
            if (updated == 0) {
                throw new BadRequestException("Service provider has no available servicemen right now.");
            }
        }

        BookingEntity booking = new BookingEntity();
        booking.setBookingRequestId(bookingRequest.getId());
        booking.setBookingCode(generateBookingCode());
        booking.setBookingType(bookingRequest.getBookingType());
        booking.setUserId(bookingRequest.getUserId());
        booking.setProviderEntityType(acceptedCandidate.getProviderEntityType());
        booking.setProviderEntityId(acceptedCandidate.getProviderEntityId());
        booking.setAddressId(bookingRequest.getAddressId());
        booking.setScheduledStartAt(bookingRequest.getScheduledStartAt());
        booking.setBookingStatus(BookingLifecycleStatus.PAYMENT_PENDING);
        booking.setPaymentStatus(PayablePaymentStatus.UNPAID);
        BigDecimal estimatedAmount = acceptedCandidate.getQuotedPriceAmount() == null
                ? BigDecimal.ZERO
                : acceptedCandidate.getQuotedPriceAmount();
        booking.setSubtotalAmount(estimatedAmount);
        booking.setTaxAmount(BigDecimal.ZERO);
        booking.setPlatformFeeAmount(BigDecimal.ZERO);
        booking.setDiscountAmount(BigDecimal.ZERO);
        booking.setTotalEstimatedAmount(estimatedAmount);
        booking.setTotalFinalAmount(null);
        booking.setCurrencyCode("INR");
        BookingEntity savedBooking = bookingRepository.save(booking);
        bookingHistoryService.recordBookingStatus(savedBooking, null, savedBooking.getBookingStatus().name(), null, "Booking created after provider acceptance");
        notificationService.notifyUser(
                bookingRequest.getUserId(),
                "BOOKING_ACCEPTED",
                "Booking accepted",
                "Your booking request has been accepted. Complete payment to confirm it.",
                java.util.Map.of("bookingId", savedBooking.getId(), "bookingCode", savedBooking.getBookingCode())
        );
        Long providerUserId = acceptedCandidate.getProviderEntityType() == ProviderEntityType.LABOUR
                ? bookingSupportRepository.findLabourUserId(acceptedCandidate.getProviderEntityId()).orElse(null)
                : bookingSupportRepository.findServiceProviderUserId(acceptedCandidate.getProviderEntityId()).orElse(null);
        if (providerUserId != null) {
            notificationService.notifyUser(
                    providerUserId,
                    "BOOKING_ASSIGNED",
                    "Booking assigned",
                    "You accepted a booking. Wait for payment confirmation.",
                    java.util.Map.of("bookingId", savedBooking.getId(), "bookingCode", savedBooking.getBookingCode())
            );
        }

        return new BookingAcceptanceData(
                bookingRequest.getId(),
                acceptedCandidate.getId(),
                savedBooking.getId(),
                savedBooking.getBookingCode(),
                savedBooking.getBookingStatus(),
                savedBooking.getPaymentStatus(),
                estimatedAmount,
                closedCount
        );
    }

    @Override
    @Transactional
    public BookingCandidateDecisionData rejectCandidate(Long actingUserId, RejectBookingCandidateRequest request) {
        BookingRequestEntity bookingRequest = bookingRequestRepository.findById(request.requestId())
                .orElseThrow(() -> new BadRequestException("Booking request not found."));
        if (bookingRequest.getRequestStatus() != BookingRequestStatus.OPEN) {
            throw new BadRequestException("Only open booking requests can be rejected by candidates.");
        }

        BookingRequestCandidateEntity candidate = bookingRequestCandidateRepository
                .findByIdAndRequestId(request.candidateId(), request.requestId())
                .orElseThrow(() -> new BadRequestException("Booking request candidate not found."));
        if (candidate.getCandidateStatus() != BookingRequestCandidateStatus.PENDING) {
            throw new BadRequestException("Only pending candidates can reject a booking request.");
        }
        validateCandidateOwner(actingUserId, candidate);

        candidate.setCandidateStatus(BookingRequestCandidateStatus.REJECTED);
        candidate.setRespondedAt(LocalDateTime.now());
        bookingRequestCandidateRepository.save(candidate);

        int pendingCount = bookingRequestCandidateRepository
                .findByRequestIdAndCandidateStatus(request.requestId(), BookingRequestCandidateStatus.PENDING)
                .size();
        if (pendingCount == 0) {
            bookingRequest.setRequestStatus(BookingRequestStatus.CANCELLED);
            bookingRequestRepository.save(bookingRequest);
            notificationService.notifyUser(
                    bookingRequest.getUserId(),
                    "BOOKING_REJECTED",
                    "Booking request closed",
                    "No provider accepted your booking request.",
                    java.util.Map.of("requestId", bookingRequest.getId(), "requestCode", bookingRequest.getRequestCode())
            );
        }

        return new BookingCandidateDecisionData(
                bookingRequest.getId(),
                candidate.getId(),
                bookingRequest.getRequestStatus(),
                candidate.getCandidateStatus(),
                pendingCount
        );
    }

    private String generateBookingCode() {
        return "BKG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private void validateCandidateOwner(Long actingUserId, BookingRequestCandidateEntity candidate) {
        Long expectedUserId = candidate.getProviderEntityType() == ProviderEntityType.LABOUR
                ? bookingSupportRepository.findLabourUserId(candidate.getProviderEntityId()).orElse(null)
                : bookingSupportRepository.findServiceProviderUserId(candidate.getProviderEntityId()).orElse(null);
        if (expectedUserId == null || !expectedUserId.equals(actingUserId)) {
            throw new BadRequestException("Authenticated user cannot act on this booking request candidate.");
        }
    }
}
