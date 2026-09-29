package com.everychurch.server.config;

import com.everychurch.server.security.BulkImportApiKeyFilter;
import com.everychurch.server.security.JwtAuthenticationFilter;
import com.everychurch.server.security.ScraperApiKeyFilter;
import jakarta.servlet.DispatcherType;
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
                // Spring Security answers "logged in but not allowed" with sendError(403); the
                // servlet container then re-dispatches to /error as an ERROR-type request.
                // If that dispatch is itself authorized it is anonymous (the JWT filter is a
                // OncePerRequestFilter and doesn't run again), so it fails and the entry point
                // overwrites the 403 with a 401 — every permission failure looked like an
                // expired session to real clients. MockMvc never does this dispatch, which is
                // why only a test on a real container (RealContainerStatusCodeTest) sees it.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Congregation-private GETs under /api/churches/{id}/ — must come before the
                // public GET rule below (first match wins), otherwise an anonymous request
                // slips through the security layer and reaches a controller with a null
                // Authentication (a 500) instead of being rejected here with a 401.
                .requestMatchers(
                        "/api/churches/*/staff/**", "/api/churches/*/members/**", "/api/churches/*/messages/**",
                        "/api/churches/*/favorite", "/api/churches/*/follow")
                    .authenticated()
                .requestMatchers("/api/churches/*/beliefs/editor").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/learn/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/learn/quiz/evaluate").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/churches/**").permitAll()
                // Same first-match-wins ordering concern as above: "/api/users/*" below is a
                // single-segment wildcard that would otherwise also match "/api/users/me"
                // (the authenticated self-profile endpoint) at the security-filter level,
                // regardless of how specifically UserController itself maps that path — this
                // rule must stay ahead of the public-profile permitAll rule.
                .requestMatchers(HttpMethod.GET, "/api/users/me", "/api/users/me/**").authenticated()
                // PublicProfileController — GET /api/users/{username}, a public profile page
                // that must work logged-out. Authentication is null here (see class comment
                // above re: anonymous auth being disabled), not merely unauthenticated.
                .requestMatchers(HttpMethod.GET, "/api/users/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
                // Client-safe config only (e.g. the Maps Embed key, restricted by HTTP
                // referrer in Google Cloud Console, not by secrecy) — see PublicConfigController.
                .requestMatchers(HttpMethod.GET, "/api/config").permitAll()
                // Church count + bulk-import progress only — no sensitive data. Backs the
                // cold-start onboarding screen, which must work for a logged-out visitor.
                .requestMatchers(HttpMethod.GET, "/api/import-status").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/r/**").permitAll()
                // The WebSocket handshake is a plain HTTP upgrade a browser won't put an
                // Authorization header on, so it can't be authenticated here. The real
                // gate is StompAuthChannelInterceptor, which requires a valid JWT on the
                // STOMP CONNECT frame and then checks every SUBSCRIBE and SEND.
                .requestMatchers("/ws/**").permitAll()
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
