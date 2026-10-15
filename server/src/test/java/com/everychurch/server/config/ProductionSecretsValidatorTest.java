package com.everychurch.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSecretsValidatorTest {

    @Test
    void allowsLocalDevelopmentDefaults() {
        ProductionSecretsValidator validator = validator(
            "", "local",
            ProductionSecretsValidator.LOCAL_DATABASE_PASSWORD,
            ProductionSecretsValidator.LOCAL_JWT_SECRET,
            ProductionSecretsValidator.LOCAL_ADMIN_PASSWORD,
            ProductionSecretsValidator.LOCAL_SCRAPER_API_KEY,
            ProductionSecretsValidator.LOCAL_BULK_IMPORT_API_KEY);

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void cloudRunRejectsMissingAndDevelopmentSecretsWithoutDisclosingValues() {
        ProductionSecretsValidator validator = validator(
            "everychurch-server", "local",
            "", "strong-jwt-secret", ProductionSecretsValidator.LOCAL_ADMIN_PASSWORD,
            "strong-scraper-key", "strong-import-key");

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        assertTrue(error.getMessage().contains("DATABASE_PASSWORD"));
        assertTrue(error.getMessage().contains("ADMIN_PASSWORD"));
        assertTrue(!error.getMessage().contains(ProductionSecretsValidator.LOCAL_ADMIN_PASSWORD));
    }

    @Test
    void productionEnvironmentAcceptsNonDefaultSecrets() {
        ProductionSecretsValidator validator = validator(
            "", "production", "db-secret", "jwt-secret", "admin-secret", "scraper-secret", "import-secret");

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    private ProductionSecretsValidator validator(
        String cloudRunService,
        String appEnvironment,
        String databasePassword,
        String jwtSecret,
        String adminPassword,
        String scraperApiKey,
        String bulkImportApiKey
    ) {
        return new ProductionSecretsValidator(
            new MockEnvironment(), cloudRunService, appEnvironment, databasePassword,
            jwtSecret, adminPassword, scraperApiKey, bulkImportApiKey);
    }
}
