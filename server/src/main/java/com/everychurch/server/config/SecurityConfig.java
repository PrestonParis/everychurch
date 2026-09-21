package com.everychurch.server.config;

import com.everychurch.server.security.BulkImportApiKeyFilter;
import com.everychurch.server.security.JwtAuthenticationFilter;
import com.everychurch.server.security.ScraperApiKeyFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * See docs/ARCHITECTURE.md "Auth & Permissions Model": stateless JWT, GET on
 * /api/churches/** and /api/events/** stays public, /r/** (the volunteer field kit's
 * redirect, Prompt 15) is public by design — no login required to use it in the
 * field — /api/scraper/** is authenticated via a shared-secret header instead of a
 * JWT (ScraperApiKeyFilter, since the scraper has no User row to log in as) —
 * /api/scraper/bulk-import specifically needs its OWN separate key/role
 * (BulkImportApiKeyFilter, hasRole("BULK_IMPORTER")) rather than the live scraper's,
 * since it's a meaningfully more trusted/dangerous path (Prompt 7) — and everything
 * else requires authentication.
 *
 * Anonymous authentication is disabled on purpose — with it enabled, an
 * unauthenticated request on a protected route is treated as an *authenticated*
 * (anonymous) principal being denied, which Spring Security reports as 403. We want
 * a clean 401 for "no/invalid credentials" vs. a future 403 for "authenticated but
 * not permitted" (e.g. a Member hitting a Platform Admin-only endpoint), so
 * anonymous auth is off and a custom entry point maps missing/invalid credentials
 * to 401.
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ScraperApiKeyFilter scraperApiKeyFilter;
    private final BulkImportApiKeyFilter bulkImportApiKeyFilter;

    public SecurityConfig(
        JwtAuthenticationFilter jwtAuthenticationFilter,
        ScraperApiKeyFilter scraperApiKeyFilter,
        BulkImportApiKeyFilter bulkImportApiKeyFilter
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.scraperApiKeyFilter = scraperApiKeyFilter;
        this.bulkImportApiKeyFilter = bulkImportApiKeyFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless JSON API, no browser form posts
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .anonymous(anonymous -> anonymous.disable())
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                (request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"Unauthorized\"}");
                }))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/api/churches/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/r/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("PLATFORM_ADMIN")
                // More specific than /api/scraper/** below — must come first, or
                // Spring Security's first-match-wins ordering would let the broader
                // hasRole("SCRAPER") rule swallow this path instead.
                .requestMatchers("/api/scraper/bulk-import").hasRole("BULK_IMPORTER")
                .requestMatchers("/api/scraper/**").hasRole("SCRAPER")
                .anyRequest().authenticated())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(scraperApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(bulkImportApiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
