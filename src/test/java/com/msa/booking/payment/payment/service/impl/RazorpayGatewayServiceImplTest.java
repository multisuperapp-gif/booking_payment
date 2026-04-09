package com.msa.booking.payment.payment.service.impl;

import com.msa.booking.payment.common.exception.BadRequestException;
import com.msa.booking.payment.config.RazorpayProperties;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RazorpayGatewayServiceImplTest {

    @Test
    void verifyPaymentSignatureReturnsTrueForExpectedSignature() {
        RazorpayGatewayServiceImpl service = new RazorpayGatewayServiceImpl(
                mock(RazorpayClient.class),
                properties("rzp_test_key", "test_secret", "webhook_secret")
        );

        boolean verified = service.verifyPaymentSignature(
                "order_ABC123",
                "pay_DEF456",
                "4bfb21c1abfb66d4dcff237baa15137fcceac96b5df4f7d7cc9ea0721dadd8e1"
        );

        assertTrue(verified);
    }

    @Test
    void verifyPaymentSignatureReturnsFalseForUnexpectedSignature() {
        RazorpayGatewayServiceImpl service = new RazorpayGatewayServiceImpl(
                mock(RazorpayClient.class),
                properties("rzp_test_key", "test_secret", "webhook_secret")
        );

        boolean verified = service.verifyPaymentSignature(
                "order_ABC123",
                "pay_DEF456",
                "wrong_signature"
        );

        assertFalse(verified);
    }

    @Test
    void verifyWebhookSignatureReturnsTrueForExpectedSignature() {
        RazorpayGatewayServiceImpl service = new RazorpayGatewayServiceImpl(
                mock(RazorpayClient.class),
                properties("rzp_test_key", "test_secret", "webhook_secret")
        );

        boolean verified = service.verifyWebhookSignature(
                "{\"event\":\"payment.captured\"}",
                "63482aecf393ec15e418daab94dffe6cd7a1ddeec5ade268106920e9f1c8363d"
        );

        assertTrue(verified);
    }

    @Test
    void verifyWebhookSignatureThrowsWhenSecretMissing() {
        RazorpayGatewayServiceImpl service = new RazorpayGatewayServiceImpl(
                mock(RazorpayClient.class),
                properties("rzp_test_key", "test_secret", "")
        );

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.verifyWebhookSignature("{\"event\":\"payment.captured\"}", "anything")
        );

        assertEquals("Razorpay webhook secret is not configured.", exception.getMessage());
    }

    private RazorpayProperties properties(String keyId, String keySecret, String webhookSecret) {
        RazorpayProperties properties = new RazorpayProperties();
        properties.setEnabled(true);
        properties.setKeyId(keyId);
        properties.setKeySecret(keySecret);
        properties.setWebhookSecret(webhookSecret);
        return properties;
    }
}
