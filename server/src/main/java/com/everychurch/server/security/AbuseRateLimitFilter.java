package com.everychurch.server.security;

import com.everychurch.server.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** Rate limits anonymous abuse-sensitive HTTP and WebSocket handshake routes by IP. */
@Component
public class AbuseRateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, Rule> POST_RULES = Map.of(
        "/api/auth/login", new Rule("auth-login", 10, Duration.ofMinutes(15)),
        "/api/auth/register", new Rule("auth-register", 5, Duration.ofHours(1)),
        "/api/auth/register/volunteer", new Rule("auth-volunteer-register", 5, Duration.ofHours(1)),
        "/api/auth/forgot-password", new Rule("auth-forgot-password", 5, Duration.ofHours(1)),
        "/api/auth/reset-password", new Rule("auth-reset-password", 10, Duration.ofHours(1)),
        "/api/learn/quiz/evaluate", new Rule("quiz-evaluate", 60, Duration.ofMinutes(1))
    );

    private static final Rule WEBSOCKET_RULE =
        new Rule("websocket-handshake", 20, Duration.ofMinutes(1));

    private final RateLimitService rateLimitService;

    public AbuseRateLimitFilter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Rule rule = ruleFor(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitService.Decision decision = rateLimitService.tryConsume(
            rule.scope(), clientAddress(request), rule.limit(), rule.window());
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Too many requests\"}");
    }

    private Rule ruleFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod())) return POST_RULES.get(path);
        if ("GET".equals(request.getMethod()) && (path.equals("/ws") || path.startsWith("/ws/"))) {
            return WEBSOCKET_RULE;
        }
        return null;
    }

    private String clientAddress(HttpServletRequest request) {
        // Cloud Run supplies X-Forwarded-For. The first address is the originating
        // client; only a conservative IP-shaped value is accepted before falling back.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            String candidate = forwarded.split(",", 2)[0].trim();
            if (candidate.length() <= 64 && candidate.matches("[0-9A-Fa-f:.]+")) {
                return candidate;
            }
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private record Rule(String scope, int limit, Duration window) {}
}
