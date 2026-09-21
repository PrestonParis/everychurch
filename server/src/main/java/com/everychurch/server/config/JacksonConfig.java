package com.everychurch.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4's auto-configuration wires up Jackson 3's JsonMapper
 * (tools.jackson.databind.json.JsonMapper), not the classic
 * com.fasterxml.jackson.databind.ObjectMapper this codebase is written against
 * (jjwt-jackson, UrlParserService, every MockMvc test that @Autowired ObjectMapper) —
 * this registers that bean explicitly so DI keeps working.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
