package com.everychurch.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates the scraper's service-to-service calls via a static shared-secret
 * header instead of a JWT — the scraper has no User row to log in as (see
 * ChurchSubmission.submittedBy, which stores the literal string "scraper" for these).
 * A missing or wrong key just leaves the request unauthenticated; SecurityConfig's
 * hasRole("SCRAPER") rule on /api/scraper/** is what actually rejects it (401).
 */
@Component
public class ScraperApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Scraper-Api-Key";

    private final String expectedKey;

    public ScraperApiKeyFilter(@Value("${scraper.api-key:}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);

        if (!expectedKey.isBlank() && expectedKey.equals(provided)) {
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_SCRAPER"));
            var authentication = new UsernamePasswordAuthenticationToken("scraper", null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
