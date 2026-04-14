package com.msa.booking.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.config.ApplicationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AuthenticatedPaymentRateLimitFilter extends OncePerRequestFilter {
    private static final String RATE_LIMITED_CODE = "RATE_LIMITED";

    private final ApplicationProperties applicationProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, RateWindow> windows = new ConcurrentHashMap<>();

    public AuthenticatedPaymentRateLimitFilter(ApplicationProperties applicationProperties, ObjectMapper objectMapper) {
        this.applicationProperties = applicationProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!applicationProperties.rateLimit().enabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return endpointRule(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        EndpointRule rule = endpointRule(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long windowMillis = applicationProperties.rateLimit().windowSeconds() * 1000L;
        long now = System.currentTimeMillis();
        String clientKey = rule.key() + ":" + clientFingerprint(request);

        RateWindow window = windows.compute(clientKey, (ignored, existing) -> {
            if (existing == null || existing.expiresAtMillis < now) {
                return new RateWindow(1, now + windowMillis);
            }
            return new RateWindow(existing.count + 1, existing.expiresAtMillis);
        });

        pruneExpiredEntries(now);

        if (window.count > rule.maxRequests()) {
            long retryAfterSeconds = Math.max(1L, (window.expiresAtMillis - now + 999L) / 1000L);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(rule.message(), RATE_LIMITED_CODE)));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void pruneExpiredEntries(long now) {
        if (windows.size() < 512) {
            return;
        }
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < now);
    }

    private String clientFingerprint(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId.trim();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return "ip:" + forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return "ip:" + realIp.trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private EndpointRule endpointRule(HttpServletRequest request) {
        ApplicationProperties.RateLimit properties = applicationProperties.rateLimit();
        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        List<EndpointRule> rules = List.of(
                new EndpointRule("POST", "/api/v1/payments/initiate", "payment-initiate", properties.paymentInitiateMaxRequests(),
                        "Too many payment initiation requests from this account. Please wait a moment and try again."),
                new EndpointRule("POST", "/api/v1/payments/verify", "payment-verify", properties.paymentVerifyMaxRequests(),
                        "Too many payment verification requests from this account. Please wait a moment and try again."),
                new EndpointRule("POST", "/api/v1/payments/failure", "payment-failure", properties.paymentFailureMaxRequests(),
                        "Too many payment failure reports from this account. Please wait a moment and try again."),
                new EndpointRule("GET", "/api/v1/payments/", "payment-status", properties.paymentStatusMaxRequests(),
                        "Too many payment status checks from this account. Please wait a moment and try again."),
                new EndpointRule("POST", "/shop-orders/cancel", "order-cancel", properties.orderCancelMaxRequests(),
                        "Too many cancel requests from this account. Please wait a moment and try again.")
        );

        for (EndpointRule rule : rules) {
            if (rule.matches(method, requestUri)) {
                return rule;
            }
        }
        return null;
    }

    private record EndpointRule(String method, String pathPrefix, String key, int maxRequests, String message) {
        private boolean matches(String requestMethod, String requestUri) {
            return method.equalsIgnoreCase(requestMethod) && requestUri.startsWith(pathPrefix);
        }
    }

    private record RateWindow(int count, long expiresAtMillis) {
    }
}
