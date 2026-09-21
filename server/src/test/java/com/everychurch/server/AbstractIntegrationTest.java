package com.everychurch.server;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Every @SpringBootTest extends this instead of wiring its own datasource. PostGIS/
 * pg_trgm (Prompt 1) are Postgres extensions with no H2 equivalent, so tests now run
 * against a real (containerized) Postgres rather than H2 — see docs/COMMANDS.md,
 * this makes Docker a hard requirement for `mvn test`.
 *
 * The container is a true singleton: started once in the static initializer below and
 * left running for the whole JVM (Testcontainers' Ryuk sidecar reaps it on exit).
 * Deliberately NOT a @Testcontainers/@Container field — that lifecycle stops the
 * container after each test class, while Spring's test context cache keeps reusing the
 * first class's ApplicationContext (its Hikari pool still pointed at the now-dead
 * container's port), so every class after the first fails with connection timeouts.
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
