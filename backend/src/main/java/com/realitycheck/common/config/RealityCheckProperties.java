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

    public record Ai(@DefaultValue("none") String provider, String apiKey) {

        public static final String NO_PROVIDER = "none";

        /** AI features are on only when a provider is named and a key is present. */
        public boolean enabled() {
            return !NO_PROVIDER.equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank();
        }

        /** Keeps the key out of logs and error messages. */
        @Override
        public String toString() {
            return "Ai[provider=" + provider + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<set>") + "]";
        }
    }
}
