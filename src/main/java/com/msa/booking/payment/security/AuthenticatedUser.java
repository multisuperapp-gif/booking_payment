package com.msa.booking.payment.security;

import java.util.Set;

public record AuthenticatedUser(
        Long userId,
        Long sessionId,
        String publicUserId,
        Set<String> roles,
        String activeRole
) {
}
