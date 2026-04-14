package com.msa.booking.payment.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccessTokenService {
    private final ObjectMapper objectMapper;
    private final String accessTokenSecret;

    public AccessTokenService(
            ObjectMapper objectMapper,
            @Value("${app.security.access-token-secret}") String accessTokenSecret
    ) {
        this.objectMapper = objectMapper;
        this.accessTokenSecret = accessTokenSecret;
    }

    public AuthenticatedUser parseToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new SecurityAuthenticationException("Invalid access token");
            }

            String expectedSignature = sign(parts[0] + "." + parts[1]);
            if (!expectedSignature.equals(parts[2])) {
                throw new SecurityAuthenticationException("Access token signature mismatch");
            }

            byte[] decodedPayload = Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> payload = objectMapper.readValue(decodedPayload, new TypeReference<>() {
            });
            long expiration = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() > expiration) {
                throw new SecurityAuthenticationException("Access token expired");
            }

            Long userId = ((Number) payload.get("sub")).longValue();
            Number sessionIdValue = (Number) payload.get("sid");
            if (sessionIdValue == null) {
                throw new SecurityAuthenticationException("Access token session is missing");
            }
            Long sessionId = sessionIdValue.longValue();
            String publicUserId = payload.get("public_user_id") == null ? null : String.valueOf(payload.get("public_user_id"));
            @SuppressWarnings("unchecked")
            Set<String> roles = payload.get("roles") instanceof List<?> roleList
                    ? Set.copyOf((List<String>) roleList)
                    : Set.of();
            String activeRole = payload.get("active_role") == null ? null : String.valueOf(payload.get("active_role"));
            return new AuthenticatedUser(userId, sessionId, publicUserId, roles, activeRole);
        } catch (SecurityAuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecurityAuthenticationException("Unable to parse access token");
        }
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(accessTokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
