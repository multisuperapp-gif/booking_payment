package com.msa.booking.payment.booking.service.impl;

import com.msa.booking.payment.booking.dto.BookingRequestCandidateInput;
import com.msa.booking.payment.booking.dto.CreateBookingRequest;
import com.msa.booking.payment.booking.service.BookingCandidateMatchingService;
import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.ProviderEntityType;
import com.msa.booking.payment.persistence.repository.LabourCandidateProjection;
import com.msa.booking.payment.persistence.repository.MatchingSearchRepository;
import com.msa.booking.payment.persistence.repository.ServiceCandidateProjection;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

@Service
public class BookingCandidateMatchingServiceImpl implements BookingCandidateMatchingService {
    private final MatchingSearchRepository matchingSearchRepository;

    public BookingCandidateMatchingServiceImpl(MatchingSearchRepository matchingSearchRepository) {
        this.matchingSearchRepository = matchingSearchRepository;
    }

    @Override
    public BookingRequestCandidateInput resolveDirectCandidate(CreateBookingRequest request) {
        if (request.targetProviderEntityType() == null || request.targetProviderEntityId() == null) {
            throw new BadRequestException("Direct booking request requires target provider.");
        }
        if (request.searchLatitude() == null || request.searchLongitude() == null) {
            throw new BadRequestException("Direct booking request requires user live location.");
        }

        return switch (request.bookingType()) {
            case LABOUR -> resolveDirectLabourCandidate(request);
            case SERVICE -> resolveDirectServiceCandidate(request);
        };
    }

    @Override
    public List<BookingRequestCandidateInput> resolveBroadcastCandidates(CreateBookingRequest request) {
        if (request.searchLatitude() == null || request.searchLongitude() == null) {
            throw new BadRequestException("Broadcast booking request requires user live location.");
        }
        return switch (request.bookingType()) {
            case LABOUR -> resolveLabourCandidates(request);
            case SERVICE -> resolveServiceCandidates(request);
        };
    }

    private List<BookingRequestCandidateInput> resolveLabourCandidates(CreateBookingRequest request) {
        if (request.categoryId() == null) {
            throw new BadRequestException("Labour broadcast booking request requires category id.");
        }
        String labourPricingModel = normalizeLabourPricingModel(request.labourPricingModel());
        List<LabourCandidateProjection> matches = matchingSearchRepository.findEligibleLabourCandidates(
                request.categoryId(),
                labourPricingModel,
                request.searchLatitude(),
                request.searchLongitude(),
                request.priceMinAmount(),
                request.priceMaxAmount()
        );
        if (matches.isEmpty()) {
            throw new BadRequestException("No labour found for the selected filters and live location.");
        }
        return matches.stream()
                .map(match -> new BookingRequestCandidateInput(
                        ProviderEntityType.LABOUR,
                        match.getProviderEntityId(),
                        normalizeAmount(match.getQuotedPriceAmount()),
                        normalizeAmount(match.getDistanceKm())
                ))
                .toList();
    }

    private BookingRequestCandidateInput resolveDirectLabourCandidate(CreateBookingRequest request) {
        List<BookingRequestCandidateInput> matches = resolveLabourCandidates(request);
        return matches.stream()
                .filter(match -> match.providerEntityId().equals(request.targetProviderEntityId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Selected labour is not eligible for booking right now."));
    }

    private List<BookingRequestCandidateInput> resolveServiceCandidates(CreateBookingRequest request) {
        if (request.categoryId() == null && request.subcategoryId() == null) {
            throw new BadRequestException("Service broadcast booking request requires category or subcategory.");
        }
        List<ServiceCandidateProjection> matches = matchingSearchRepository.findEligibleServiceCandidates(
                request.categoryId(),
                request.subcategoryId(),
                request.searchLatitude(),
                request.searchLongitude(),
                request.priceMinAmount(),
                request.priceMaxAmount()
        );
        if (matches.isEmpty()) {
            throw new BadRequestException("No service provider found for the selected filters and live location.");
        }
        return matches.stream()
                .map(match -> new BookingRequestCandidateInput(
                        ProviderEntityType.SERVICE_PROVIDER,
                        match.getProviderEntityId(),
                        normalizeAmount(match.getQuotedPriceAmount()),
                        normalizeAmount(match.getDistanceKm())
                ))
                .toList();
    }

    private BookingRequestCandidateInput resolveDirectServiceCandidate(CreateBookingRequest request) {
        List<BookingRequestCandidateInput> matches = resolveServiceCandidates(request);
        return matches.stream()
                .filter(match -> match.providerEntityId().equals(request.targetProviderEntityId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Selected service provider is not eligible for booking right now."));
    }

    private String normalizeLabourPricingModel(String labourPricingModel) {
        if (!StringUtils.hasText(labourPricingModel)) {
            throw new BadRequestException("Labour booking request requires pricing model.");
        }
        return switch (labourPricingModel.trim().toUpperCase().replace(' ', '_')) {
            case "HOURLY" -> "HOURLY";
            case "HALF_DAY" -> "HALF_DAY";
            case "FULL_DAY" -> "FULL_DAY";
            default -> throw new BadRequestException("Unsupported labour pricing model.");
        };
    }

    private BigDecimal normalizeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
