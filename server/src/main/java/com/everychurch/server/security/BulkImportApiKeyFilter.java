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
 * Authenticates the one-time bulk-import script's calls (Prompt 7, docs/SCRAPER.md
 * "Bulk import") via a static shared-secret header — same pattern as
 * ScraperApiKeyFilter, but a SEPARATE, independently-revocable secret. Deliberately
 * not the live scraper's key: this path writes Church rows directly (claimed=false),
 * skipping the ChurchSubmission review queue entirely, so it's meaningfully more
 * trusted/dangerous than day-to-day scraper discovery and shouldn't share a blast
 * radius with it.
 */
@Component
public class BulkImportApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Bulk-Import-Api-Key";

    private final String expectedKey;

    public BulkImportApiKeyFilter(@Value("${bulk-import.api-key:}") String expectedKey) {
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
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_BULK_IMPORTER"));
            var authentication = new UsernamePasswordAuthenticationToken("bulk-import", null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
