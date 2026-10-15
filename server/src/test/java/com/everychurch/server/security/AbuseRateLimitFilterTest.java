package com.everychurch.server.security;

import com.everychurch.server.service.RateLimitService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbuseRateLimitFilterTest {

    private final RateLimitService limiter = mock(RateLimitService.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final AbuseRateLimitFilter filter = new AbuseRateLimitFilter(limiter);

    @Test
    void rejectsLimitedLoginWithRetryAfter() throws Exception {
        when(limiter.tryConsume(anyString(), anyString(), anyInt(), any()))
            .thenReturn(new RateLimitService.Decision(false, 42));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("42", response.getHeader("Retry-After"));
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void ignoresUnrelatedRoutes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/churches");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(limiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
    }
}
