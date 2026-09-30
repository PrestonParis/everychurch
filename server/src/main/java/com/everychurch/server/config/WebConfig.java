package com.everychurch.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.LinkedHashSet;
import java.util.Set;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String clientBaseUrl;

    public WebConfig(@Value("${app.client-base-url:http://localhost:4200}") String clientBaseUrl) {
        this.clientBaseUrl = clientBaseUrl;
    }

    /**
     * Browsers put an Origin header on every same-origin POST/PUT/PATCH/DELETE, even
     * though Vercel proxies /api/** so the app and API share an origin. Spring's CORS
     * processor rejects any Origin not listed here with a plain-text 403 ("Invalid
     * CORS request") before the controller runs — with only localhost:4200 listed,
     * every production login, registration, and other write failed that way. So the
     * deployed client (and its www/apex twin, which Vercel serves too) must be listed.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins().toArray(String[]::new))
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*");
    }

    Set<String> allowedOrigins() {
        Set<String> origins = new LinkedHashSet<>();
        origins.add("http://localhost:4200");
        String base = clientBaseUrl.replaceAll("/+$", "");
        origins.add(base);
        if (base.startsWith("https://www.")) {
            origins.add("https://" + base.substring("https://www.".length()));
        } else if (base.startsWith("https://")) {
            origins.add("https://www." + base.substring("https://".length()));
        }
        return origins;
    }
}
