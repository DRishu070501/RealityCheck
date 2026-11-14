package com.realitycheck.common.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(RealityCheckProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final RealityCheckProperties properties;

    public WebConfig(RealityCheckProperties properties) {
        this.properties = properties;
    }

    /**
     * Only the configured origins may call the API from a browser. In the Docker deployment the
     * frontend proxies {@code /api} on its own origin, so no cross-origin access is needed there.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept", "Idempotency-Key", "If-Match")
                .exposedHeaders("Location", "ETag")
                .maxAge(3600);
    }

    /**
     * The one source of "now" for application services. The simulation engine never uses it.
     * Ticks in microseconds, the precision PostgreSQL stores, so a value read back equals the
     * value written.
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
