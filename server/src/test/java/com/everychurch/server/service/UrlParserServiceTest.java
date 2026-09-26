package com.everychurch.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF guard added in review (2026-09-22): POST /api/churches/parse-url fetches a
 * user-supplied URL server-side for any authenticated user, with no check that it
 * isn't pointed at a private/internal address (cloud instance metadata, an internal
 * admin panel, etc.). These exercise validateAndResolve via the public parse() entry
 * point, using literal IP addresses/loopback hostnames specifically so resolution is
 * instant and these tests need zero network I/O — no live server, no flakiness.
 */
class UrlParserServiceTest {

    private final UrlParserService service = new UrlParserService();

    @Test
    void rejectsLoopbackAddresses() {
        assertRejected("http://127.0.0.1/church");
        assertRejected("http://localhost/church");
        assertRejected("http://[::1]/church");
    }

    @Test
    void rejectsLinkLocalAddressesIncludingCloudInstanceMetadata() {
        // 169.254.169.254 is the AWS/GCP/Azure instance metadata endpoint — the
        // single most common real-world SSRF target.
        assertRejected("http://169.254.169.254/latest/meta-data/");
    }

    @Test
    void rejectsRfc1918PrivateAddresses() {
        assertRejected("http://10.0.0.5/");
        assertRejected("http://172.16.0.1/");
        assertRejected("http://192.168.1.1/");
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertRejected("file:///etc/passwd");
        assertRejected("ftp://example.com/church");
        assertRejected("gopher://127.0.0.1:6379/_flushall");
    }

    @Test
    void rejectsMalformedOrHostlessUrls() {
        assertRejected("not a url");
        assertRejected("http://");
    }

    private void assertRejected(String url) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.parse(url));
        assertTrue(e.getMessage() != null && !e.getMessage().isBlank(), "expected a clear rejection message for " + url);
    }
}
