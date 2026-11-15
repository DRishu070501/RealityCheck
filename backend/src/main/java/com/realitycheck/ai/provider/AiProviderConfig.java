package com.realitycheck.ai.provider;

import com.realitycheck.common.config.RealityCheckProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the providers from {@code realitycheck.ai.*}. The template analyst always exists; a
 * language model is added only when a supported provider is named and a key is present.
 */
@Configuration
public class AiProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(AiProviderConfig.class);

    /**
     * @param languageModel null when none is configured: the template analyst answers everything
     */
    public record AiProviders(AiProvider languageModel, TemplateAiProvider template) {

        public AiProvider active() {
            return languageModel != null ? languageModel : template;
        }
    }

    @Bean
    AiProviders aiProviders(RealityCheckProperties properties) {
        return select(properties.ai());
    }

    static AiProviders select(RealityCheckProperties.Ai ai) {
        TemplateAiProvider template = new TemplateAiProvider();
        String name = ai.providerName();
        if (!RealityCheckProperties.Ai.TEMPLATE.equals(name) && !ai.enabled()) {
            if (!RealityCheckProperties.Ai.LANGUAGE_MODEL_PROVIDERS.contains(name)) {
                log.warn("AI_PROVIDER '{}' is not supported; the built-in template analyst will answer", name);
            } else {
                log.warn("AI_PROVIDER is '{}' but AI_API_KEY is empty; the built-in template analyst will answer",
                        name);
            }
            return new AiProviders(null, template);
        }
        if (!ai.enabled()) {
            log.info("AI analyst: built-in template (no language model)");
            return new AiProviders(null, template);
        }
        log.info("AI analyst: {} model {}", name, ai.model());
        AiProvider model = switch (name) {
            case AnthropicAiProvider.NAME -> new AnthropicAiProvider(ai.apiKey(), ai.model(), ai.baseUrl(),
                    Duration.ofSeconds(ai.timeoutSeconds()), ai.maxRetries());
            default -> throw new IllegalStateException("Unhandled provider " + name);
        };
        return new AiProviders(model, template);
    }
}
