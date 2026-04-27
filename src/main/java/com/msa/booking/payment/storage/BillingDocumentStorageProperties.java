package com.msa.booking.payment.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.storage")
public class BillingDocumentStorageProperties {
    private String provider = "LOCAL";
    private String localRoot = "../Auth-VerificationService/storage/local";
    private String awsRegion = "us-east-1";
    private final BucketProperties billingDocs = new BucketProperties();
    private final BucketProperties tempUpload = new BucketProperties();
    private final PrefixProperties prefixes = new PrefixProperties();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(String localRoot) {
        this.localRoot = localRoot;
    }

    public String getAwsRegion() {
        return awsRegion;
    }

    public void setAwsRegion(String awsRegion) {
        this.awsRegion = awsRegion;
    }

    public BucketProperties getBillingDocs() {
        return billingDocs;
    }

    public BucketProperties getTempUpload() {
        return tempUpload;
    }

    public PrefixProperties getPrefixes() {
        return prefixes;
    }

    public static class BucketProperties {
        private String bucket = "";

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }
    }

    public static class PrefixProperties {
        private String labourInvoices = "invoices/labour";
        private String serviceInvoices = "invoices/service";
        private String shopInvoices = "invoices/shop";
        private String refundCreditNotes = "refunds/credit-notes";
        private String settlements = "settlements";
        private String multipart = "multipart";

        public String getLabourInvoices() {
            return labourInvoices;
        }

        public void setLabourInvoices(String labourInvoices) {
            this.labourInvoices = labourInvoices;
        }

        public String getServiceInvoices() {
            return serviceInvoices;
        }

        public void setServiceInvoices(String serviceInvoices) {
            this.serviceInvoices = serviceInvoices;
        }

        public String getShopInvoices() {
            return shopInvoices;
        }

        public void setShopInvoices(String shopInvoices) {
            this.shopInvoices = shopInvoices;
        }

        public String getRefundCreditNotes() {
            return refundCreditNotes;
        }

        public void setRefundCreditNotes(String refundCreditNotes) {
            this.refundCreditNotes = refundCreditNotes;
        }

        public String getSettlements() {
            return settlements;
        }

        public void setSettlements(String settlements) {
            this.settlements = settlements;
        }

        public String getMultipart() {
            return multipart;
        }

        public void setMultipart(String multipart) {
            this.multipart = multipart;
        }
    }
}
