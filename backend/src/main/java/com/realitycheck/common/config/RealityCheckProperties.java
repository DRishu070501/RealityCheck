package com.realitycheck.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application settings under the {@code realitycheck.*} prefix. Values come from environment
 * variables through {@code application.yml}; nothing here is ever serialised to a client.
 */
@ConfigurationProperties(prefix = "realitycheck")
public record RealityCheckProperties(@DefaultValue Cors cors, @DefaultValue Ai ai, @DefaultValue Demo demo) {

    /**
     * @param seedOnStartup create the demonstration factory at start-up when the database has no factory
     * @param resetEnabled  register {@code POST /api/demo/reset}, which erases all data and re-seeds
     */
    public record Demo(@DefaultValue("false") boolean seedOnStartup, @DefaultValue("false") boolean resetEnabled) {
    }

    public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
    }

    /**
     * The AI layer. The built-in template analyst is used unless a language-model provider is
     * named and a key is present.
     *
     * @param provider          {@code template} (built in, no network), {@code anthropic}; {@code none} is
     *                          read as {@code template}
     * @param apiKey            read from the environment only; never logged or returned
     * @param model             the provider's model name, for example {@code claude-haiku-5-5}
     * @param baseUrl           overrides the provider's API address; empty for the default
     * @param timeoutSeconds    per request, including reading the whole response
     * @param maxRetries        transport retries on timeouts, 429 and 5xx, with backoff
     * @param maxOutputTokens   upper bound on the length of one answer
     * @param requestsPerMinute AI requests accepted per minute across all callers; more get 429
     */
    public record Ai(
            @DefaultValue(TEMPLATE) String provider,
            String apiKey,
            @DefaultValue("claude-haiku-5-5") String model,
            String baseUrl,
            @DefaultValue("30") int timeoutSeconds,
            @DefaultValue("2") int maxRetries,
            @DefaultValue("4000") int maxOutputTokens,
            @DefaultValue("20") int requestsPerMinute) {

        public static final String TEMPLATE = "template";
        public static final String NO_PROVIDER = "none";

        /** Defaults for everything except the provider and key; for tests. */
        public static Ai of(String provider, String apiKey) {
            return new Ai(provider, apiKey, "claude-haiku-5-5", null, 30, 2, 4000, 20);
        }

        /** The model name when a language model is in use; null for the template analyst. */
        public String activeModel() {
            return enabled() ? model : null;
        }

        /** The provider that answers: the configured one when enabled, otherwise the template analyst. */
        public String activeProvider() {
            return enabled() ? providerName() : TEMPLATE;
        }

        /** The provider name as configured, with {@code none} and blank read as {@code template}. */
        public String providerName() {
            if (provider == null || provider.isBlank() || NO_PROVIDER.equalsIgnoreCase(provider.trim())) {
                return TEMPLATE;
            }
            return provider.trim().toLowerCase(java.util.Locale.ROOT);
        }

        /** Language-model vendors this build can call. */
        public static final java.util.Set<String> LANGUAGE_MODEL_PROVIDERS = java.util.Set.of("anthropic");

        /** True when a supported language-model provider is named and a key is present. */
        public boolean enabled() {
            return LANGUAGE_MODEL_PROVIDERS.contains(providerName()) && hasKey();
        }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** Keeps the key out of logs and error messages. */
        @Override
        public String toString() {
            return "Ai[provider=" + provider + ", apiKey=" + (hasKey() ? "<set>" : "<unset>") + ", model=" + model
                    + ", baseUrl=" + baseUrl + ", timeoutSeconds=" + timeoutSeconds + ", maxRetries=" + maxRetries
                    + ", maxOutputTokens=" + maxOutputTokens + ", requestsPerMinute=" + requestsPerMinute + "]";
        }
    }
}
