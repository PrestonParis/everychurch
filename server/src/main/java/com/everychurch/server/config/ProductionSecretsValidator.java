package com.everychurch.server.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Prevents a deployed service from silently starting with the local-development
 * credentials from application.properties. Cloud Run always supplies K_SERVICE;
 * other hosts can opt into the same check with APP_ENVIRONMENT=production or the
 * Spring "prod" profile.
 *
 * The exception deliberately names only variables, never their values.
 */
@Component
public class ProductionSecretsValidator implements InitializingBean {

    static final String LOCAL_DATABASE_PASSWORD = "everychurch";
    static final String LOCAL_JWT_SECRET = "dev-only-insecure-secret-change-me-0123456789abcdef";
    static final String LOCAL_ADMIN_PASSWORD = "change-me-now";
    static final String LOCAL_SCRAPER_API_KEY = "dev-only-insecure-scraper-key-change-me";
    static final String LOCAL_BULK_IMPORT_API_KEY = "dev-only-insecure-bulk-import-key-change-me";

    private final boolean production;
    private final String databasePassword;
    private final String jwtSecret;
    private final String adminPassword;
    private final String scraperApiKey;
    private final String bulkImportApiKey;

    public ProductionSecretsValidator(
        Environment environment,
        @Value("${K_SERVICE:}") String cloudRunService,
        @Value("${app.environment:local}") String appEnvironment,
        @Value("${spring.datasource.password:}") String databasePassword,
        @Value("${jwt.secret:}") String jwtSecret,
        @Value("${admin.seed.password:}") String adminPassword,
        @Value("${scraper.api-key:}") String scraperApiKey,
        @Value("${bulk-import.api-key:}") String bulkImportApiKey
    ) {
        this.production = !cloudRunService.isBlank()
            || "production".equalsIgnoreCase(appEnvironment)
            || "prod".equalsIgnoreCase(appEnvironment)
            || environment.acceptsProfiles(Profiles.of("prod"));
        this.databasePassword = databasePassword;
        this.jwtSecret = jwtSecret;
        this.adminPassword = adminPassword;
        this.scraperApiKey = scraperApiKey;
        this.bulkImportApiKey = bulkImportApiKey;
    }

    @Override
    public void afterPropertiesSet() {
        if (!production) return;

        List<String> unsafeVariables = new ArrayList<>();
        requireSecret("DATABASE_PASSWORD", databasePassword, LOCAL_DATABASE_PASSWORD, unsafeVariables);
        requireSecret("JWT_SECRET", jwtSecret, LOCAL_JWT_SECRET, unsafeVariables);
        requireSecret("ADMIN_PASSWORD", adminPassword, LOCAL_ADMIN_PASSWORD, unsafeVariables);
        requireSecret("SCRAPER_API_KEY", scraperApiKey, LOCAL_SCRAPER_API_KEY, unsafeVariables);
        requireSecret("BULK_IMPORT_API_KEY", bulkImportApiKey, LOCAL_BULK_IMPORT_API_KEY, unsafeVariables);

        if (!unsafeVariables.isEmpty()) {
            throw new IllegalStateException(
                "Refusing to start outside local development: missing or insecure secret variables: "
                    + String.join(", ", unsafeVariables));
        }
    }

    private static void requireSecret(String variable, String value, String localDefault, List<String> unsafeVariables) {
        if (value == null || value.isBlank() || localDefault.equals(value)) {
            unsafeVariables.add(variable);
        }
    }
}
