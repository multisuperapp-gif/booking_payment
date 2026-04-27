package com.msa.booking.payment.storage;

import com.msa.booking.payment.domain.enums.BookingFlowType;
import com.msa.booking.payment.domain.enums.PayableType;
import com.msa.booking.payment.persistence.entity.BookingEntity;
import com.msa.booking.payment.persistence.entity.BookingRequestEntity;
import com.msa.booking.payment.persistence.entity.PaymentEntity;
import com.msa.booking.payment.persistence.entity.RefundEntity;
import com.msa.booking.payment.persistence.entity.SettlementEntity;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Service
public class BillingDocumentStorageService {
    private static final String STORAGE_PROVIDER_S3 = "S3";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BillingDocumentStorageProperties properties;
    private volatile S3Client s3Client;
    private volatile S3Presigner s3Presigner;

    public BillingDocumentStorageService(BillingDocumentStorageProperties properties) {
        this.properties = properties;
    }

    public BillingDocumentLink storePaymentInvoice(
            PaymentEntity payment,
            BookingEntity booking,
            BookingRequestEntity bookingRequest,
            String referenceCode,
            String description
    ) {
        String prefix = invoicePrefix(payment, booking, bookingRequest);
        String objectKey = buildObjectKey(prefix, completedAtOrNow(payment.getCompletedAt(), payment.getInitiatedAt()), payment.getPaymentCode() + ".html");
        String title = "Invoice " + payment.getPaymentCode();
        String payableLabel = switch (payment.getPayableType()) {
            case SHOP_ORDER -> "Shop order";
            case BOOKING -> booking == null ? "Booking" : booking.getBookingType().name().toLowerCase(Locale.ROOT) + " booking";
            case BOOKING_REQUEST -> bookingRequest == null ? "Booking request" : bookingRequest.getBookingType().name().toLowerCase(Locale.ROOT) + " booking request";
        };
        String html = wrapHtml(
                title,
                """
                <h1>%s</h1>
                <p><strong>Document type:</strong> Invoice</p>
                <p><strong>Payment code:</strong> %s</p>
                <p><strong>Reference:</strong> %s</p>
                <p><strong>Payable type:</strong> %s</p>
                <p><strong>Status:</strong> %s</p>
                <p><strong>Amount:</strong> %s %s</p>
                <p><strong>Payer user:</strong> %s</p>
                <p><strong>Initiated at:</strong> %s</p>
                <p><strong>Completed at:</strong> %s</p>
                <p><strong>Note:</strong> %s</p>
                """.formatted(
                        escape(title),
                        escape(payment.getPaymentCode()),
                        escape(referenceCode),
                        escape(payableLabel),
                        escape(payment.getPaymentStatus().name()),
                        escape(payment.getCurrencyCode()),
                        escape(payment.getAmount().toPlainString()),
                        escape(String.valueOf(payment.getPayerUserId())),
                        formatTs(payment.getInitiatedAt()),
                        formatTs(payment.getCompletedAt()),
                        escape(description == null ? "Payment completed successfully." : description)
                )
        );
        storeHtml(objectKey, html);
        return link(objectKey);
    }

    public BillingDocumentLink storeRefundCreditNote(
            PaymentEntity payment,
            RefundEntity refund,
            String referenceCode,
            String description
    ) {
        String objectKey = buildObjectKey(
                normalizePrefix(properties.getPrefixes().getRefundCreditNotes()),
                completedAtOrNow(refund.getCompletedAt(), refund.getInitiatedAt()),
                refund.getRefundCode() + ".html"
        );
        String html = wrapHtml(
                "Credit note " + refund.getRefundCode(),
                """
                <h1>%s</h1>
                <p><strong>Document type:</strong> Credit note</p>
                <p><strong>Refund code:</strong> %s</p>
                <p><strong>Payment code:</strong> %s</p>
                <p><strong>Reference:</strong> %s</p>
                <p><strong>Status:</strong> %s</p>
                <p><strong>Requested amount:</strong> %s %s</p>
                <p><strong>Approved amount:</strong> %s %s</p>
                <p><strong>Reason:</strong> %s</p>
                <p><strong>Started at:</strong> %s</p>
                <p><strong>Completed at:</strong> %s</p>
                <p><strong>Note:</strong> %s</p>
                """.formatted(
                        escape("Credit note " + refund.getRefundCode()),
                        escape(refund.getRefundCode()),
                        escape(payment.getPaymentCode()),
                        escape(referenceCode),
                        escape(refund.getRefundStatus().name()),
                        escape(payment.getCurrencyCode()),
                        escape(zeroSafe(refund.getRequestedAmount())),
                        escape(payment.getCurrencyCode()),
                        escape(zeroSafe(refund.getApprovedAmount())),
                        escape(refund.getReason()),
                        formatTs(refund.getInitiatedAt()),
                        formatTs(refund.getCompletedAt()),
                        escape(description == null ? "Refund completed." : description)
                )
        );
        storeHtml(objectKey, html);
        return link(objectKey);
    }

    public BillingDocumentLink storeSettlementStatement(SettlementEntity settlement, String description) {
        String objectKey = buildObjectKey(
                normalizePrefix(properties.getPrefixes().getSettlements()),
                completedAtOrNow(settlement.getPaidAt(), settlement.getCreatedAt()),
                settlement.getSettlementCode() + ".html"
        );
        String html = wrapHtml(
                "Settlement " + settlement.getSettlementCode(),
                """
                <h1>%s</h1>
                <p><strong>Document type:</strong> Settlement statement</p>
                <p><strong>Settlement code:</strong> %s</p>
                <p><strong>Beneficiary type:</strong> %s</p>
                <p><strong>Beneficiary id:</strong> %s</p>
                <p><strong>Status:</strong> %s</p>
                <p><strong>Gross amount:</strong> %s</p>
                <p><strong>Commission amount:</strong> %s</p>
                <p><strong>Tax amount:</strong> %s</p>
                <p><strong>Adjustment amount:</strong> %s</p>
                <p><strong>Refund deduction:</strong> %s</p>
                <p><strong>Net amount:</strong> %s</p>
                <p><strong>Created at:</strong> %s</p>
                <p><strong>Paid at:</strong> %s</p>
                <p><strong>Note:</strong> %s</p>
                """.formatted(
                        escape("Settlement " + settlement.getSettlementCode()),
                        escape(settlement.getSettlementCode()),
                        escape(settlement.getBeneficiaryType()),
                        escape(String.valueOf(settlement.getBeneficiaryId())),
                        escape(settlement.getStatus()),
                        escape(zeroSafe(settlement.getGrossAmount())),
                        escape(zeroSafe(settlement.getCommissionAmount())),
                        escape(zeroSafe(settlement.getTaxAmount())),
                        escape(zeroSafe(settlement.getAdjustmentAmount())),
                        escape(zeroSafe(settlement.getRefundDeductionAmount())),
                        escape(zeroSafe(settlement.getNetAmount())),
                        formatTs(settlement.getCreatedAt()),
                        formatTs(settlement.getPaidAt()),
                        escape(description == null ? "Settlement updated." : description)
                )
        );
        storeHtml(objectKey, html);
        return link(objectKey);
    }

    public BillingDocumentLink resolvePaymentInvoiceLink(
            PaymentEntity payment,
            BookingEntity booking,
            BookingRequestEntity bookingRequest
    ) {
        String prefix = invoicePrefix(payment, booking, bookingRequest);
        String objectKey = buildObjectKey(prefix, completedAtOrNow(payment.getCompletedAt(), payment.getInitiatedAt()), payment.getPaymentCode() + ".html");
        return link(objectKey);
    }

    public BillingDocumentLink resolveRefundCreditNoteLink(RefundEntity refund) {
        if (refund == null || refund.getRefundCode() == null || refund.getRefundCode().isBlank()) {
            return new BillingDocumentLink(null, null);
        }
        String objectKey = buildObjectKey(
                normalizePrefix(properties.getPrefixes().getRefundCreditNotes()),
                completedAtOrNow(refund.getCompletedAt(), refund.getInitiatedAt()),
                refund.getRefundCode() + ".html"
        );
        return link(objectKey);
    }

    public BillingDocumentLink resolveSettlementStatementLink(SettlementEntity settlement) {
        if (settlement == null || settlement.getSettlementCode() == null || settlement.getSettlementCode().isBlank()) {
            return new BillingDocumentLink(null, null);
        }
        String objectKey = buildObjectKey(
                normalizePrefix(properties.getPrefixes().getSettlements()),
                completedAtOrNow(settlement.getPaidAt(), settlement.getCreatedAt()),
                settlement.getSettlementCode() + ".html"
        );
        return link(objectKey);
    }

    private String invoicePrefix(PaymentEntity payment, BookingEntity booking, BookingRequestEntity bookingRequest) {
        if (payment.getPayableType() == PayableType.SHOP_ORDER) {
            return normalizePrefix(properties.getPrefixes().getShopInvoices());
        }
        BookingFlowType bookingType = booking != null ? booking.getBookingType() : bookingRequest == null ? null : bookingRequest.getBookingType();
        if (bookingType == BookingFlowType.LABOUR) {
            return normalizePrefix(properties.getPrefixes().getLabourInvoices());
        }
        return normalizePrefix(properties.getPrefixes().getServiceInvoices());
    }

    private BillingDocumentLink link(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return new BillingDocumentLink(null, null);
        }
        if (!useS3()) {
            return new BillingDocumentLink(objectKey, null);
        }
        try {
            URL presigned = s3Presigner().presignGetObject(
                    GetObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(20))
                            .getObjectRequest(
                                    GetObjectRequest.builder()
                                            .bucket(properties.getBillingDocs().getBucket())
                                            .key(objectKey)
                                            .build()
                            )
                            .build()
            ).url();
            return new BillingDocumentLink(objectKey, presigned.toString());
        } catch (RuntimeException exception) {
            return new BillingDocumentLink(objectKey, null);
        }
    }

    private void storeHtml(String objectKey, String html) {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        if (useS3()) {
            s3Client().putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBillingDocs().getBucket())
                            .key(objectKey)
                            .contentType("text/html; charset=utf-8")
                            .build(),
                    RequestBody.fromBytes(bytes)
            );
            return;
        }
        Path destination = Path.of(properties.getLocalRoot()).normalize()
                .resolve(properties.getBillingDocs().getBucket())
                .resolve(objectKey);
        try {
            Files.createDirectories(destination.getParent());
            Files.write(destination, bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to store billing document locally", exception);
        }
    }

    private boolean useS3() {
        return STORAGE_PROVIDER_S3.equalsIgnoreCase(properties.getProvider());
    }

    private String buildObjectKey(String prefix, LocalDateTime timestamp, String filename) {
        LocalDateTime effective = timestamp == null ? LocalDateTime.now() : timestamp;
        return "%s/%d/%02d/%s".formatted(prefix, effective.getYear(), effective.getMonthValue(), filename);
    }

    private String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.trim();
        value = value.replace("\\", "/");
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        return value.isBlank() ? "billing" : value;
    }

    private LocalDateTime completedAtOrNow(LocalDateTime preferred, LocalDateTime fallback) {
        if (preferred != null) {
            return preferred;
        }
        if (fallback != null) {
            return fallback;
        }
        return LocalDateTime.now();
    }

    private String wrapHtml(String title, String body) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    body { font-family: Arial, sans-serif; margin: 32px; color: #1f2937; }
                    h1 { margin-bottom: 20px; }
                    p { margin: 8px 0; line-height: 1.5; }
                    strong { display: inline-block; min-width: 160px; }
                  </style>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(escape(title), body);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String formatTs(LocalDateTime value) {
        return value == null ? "" : TS.format(value);
    }

    private String zeroSafe(java.math.BigDecimal value) {
        return value == null ? "0.00" : value.toPlainString();
    }

    private S3Client s3Client() {
        S3Client existing = s3Client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (s3Client == null) {
                s3Client = S3Client.builder()
                        .region(Region.of(properties.getAwsRegion()))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();
            }
            return s3Client;
        }
    }

    private S3Presigner s3Presigner() {
        S3Presigner existing = s3Presigner;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (s3Presigner == null) {
                s3Presigner = S3Presigner.builder()
                        .region(Region.of(properties.getAwsRegion()))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();
            }
            return s3Presigner;
        }
    }
}
