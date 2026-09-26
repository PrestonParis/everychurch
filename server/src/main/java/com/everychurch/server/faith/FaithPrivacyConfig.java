package com.everychurch.server.faith;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class FaithPrivacyConfig implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                response.setHeader("Cache-Control","no-store");
                response.setHeader("Referrer-Policy","no-referrer");
                return true;
            }
        }).addPathPatterns("/api/learn/quiz/evaluate","/api/users/me/quiz-attempts/**","/api/churches/*/beliefs/editor","/api/churches/*/beliefs/draft");
    }
}
