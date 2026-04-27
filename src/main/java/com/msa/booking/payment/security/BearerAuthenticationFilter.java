package com.msa.booking.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msa.booking.payment.common.api.ApiResponse;
import com.msa.booking.payment.persistence.entity.UserSessionEntity;
import com.msa.booking.payment.persistence.repository.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokenService accessTokenService;
    private final UserSessionRepository userSessionRepository;
    private final ObjectMapper objectMapper;

    public BearerAuthenticationFilter(
            AccessTokenService accessTokenService,
            UserSessionRepository userSessionRepository,
            ObjectMapper objectMapper
    ) {
        this.accessTokenService = accessTokenService;
        this.userSessionRepository = userSessionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7);
            try {
                AuthenticatedUser authenticatedUser = accessTokenService.parseToken(token);
                validateSession(authenticatedUser);
                validateUserHeader(authenticatedUser, request.getHeader("X-User-Id"));
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        authenticatedUser,
                        token,
                        authenticatedUser.roles().stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet())
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (SecurityAuthenticationException exception) {
                writeUnauthorized(response, exception.getMessage());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void validateSession(AuthenticatedUser authenticatedUser) {
        UserSessionEntity session = userSessionRepository
                .findByIdAndUserId(authenticatedUser.sessionId(), authenticatedUser.userId())
                .orElse(null);
        if (session == null) {
            throw new SecurityAuthenticationException("Active session not found for access token");
        }
        if (session.getRevokedAt() != null) {
            throw new SecurityAuthenticationException("Session has been revoked");
        }
        Instant expiresAt = session.getExpiresAt() == null ? null : session.getExpiresAt().toInstant();
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            throw new SecurityAuthenticationException("Session has expired");
        }
    }

    private void validateUserHeader(AuthenticatedUser authenticatedUser, String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return;
        }
        if (!String.valueOf(authenticatedUser.userId()).equals(userIdHeader.trim())) {
            throw new SecurityAuthenticationException("Authenticated user does not match request user");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(message, "UNAUTHORIZED")));
    }
}
