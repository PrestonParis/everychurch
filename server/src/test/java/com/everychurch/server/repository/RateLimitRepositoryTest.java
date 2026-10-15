package com.everychurch.server.repository;

import com.everychurch.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RateLimitRepositoryTest extends AbstractIntegrationTest {

    @Autowired RateLimitRepository repository;

    @Test
    void incrementsAtomicallyAndResetsAtANewWindow() {
        String key = UUID.randomUUID().toString().replace("-", "");
        Instant firstWindow = Instant.parse("2026-10-15T12:00:00Z");

        assertEquals(1, repository.increment(key, firstWindow));
        assertEquals(2, repository.increment(key, firstWindow));
        assertEquals(1, repository.increment(key, firstWindow.plusSeconds(60)));
    }
}
