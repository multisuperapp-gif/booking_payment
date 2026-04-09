package com.msa.booking.payment.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(FirebaseProperties.class)
public class FirebaseAdminConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(FirebaseAdminConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "app.firebase", name = "enabled", havingValue = "true")
    public FirebaseApp firebaseApp(FirebaseProperties properties) throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }

        String serviceAccountPath = properties.getServiceAccountPath();
        if (serviceAccountPath == null || serviceAccountPath.isBlank()) {
            throw new IllegalStateException("app.firebase.service-account-path must be configured when Firebase is enabled");
        }

        Path credentialPath = Path.of(serviceAccountPath.trim());
        if (!Files.exists(credentialPath)) {
            throw new IllegalStateException("Firebase service account file not found at: " + credentialPath);
        }

        try (InputStream inputStream = Files.newInputStream(credentialPath)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(inputStream))
                    .build();
            FirebaseApp firebaseApp = FirebaseApp.initializeApp(options);
            LOGGER.info("Firebase Admin initialized from {}", credentialPath);
            return firebaseApp;
        }
    }
}
