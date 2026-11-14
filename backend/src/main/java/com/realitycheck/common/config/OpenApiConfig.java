package com.realitycheck.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI realityCheckOpenApi() {
        return new OpenAPI().info(new Info()
                .title("RealityCheck API")
                .version("v1")
                .description("""
                        Business operations digital twin. Numeric results are produced only by the \
                        deterministic simulation engine or read from recorded history; AI output is \
                        interpretation and is labelled as such."""));
    }
}
