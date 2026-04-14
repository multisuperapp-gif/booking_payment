package com.msa.booking.payment.security;

public class SecurityAuthenticationException extends RuntimeException {
    public SecurityAuthenticationException(String message) {
        super(message);
    }
}
