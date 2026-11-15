package com.realitycheck.common.system;

import java.time.Instant;

public record SystemInfoResponse(
        String application,
        String version,
        Instant serverTime,
        Database database,
        Migrations migrations,
        Ai ai,
        Demo demo) {

    /** Whether this deployment allows the demonstration data to be reset from the UI. */
    public record Demo(boolean resetEnabled) {
    }

    /** {@code product} and {@code version} are null when the database cannot be reached. */
    public record Database(String status, String product, String version) {

        public static final String UP = "UP";
        public static final String DOWN = "DOWN";
    }

    /** {@code currentVersion} is null when no migration has been applied or the database is down. */
    public record Migrations(int appliedCount, String currentVersion) {
    }

    /**
     * The AI analyst. Never includes credentials.
     *
     * @param enabled   a language model is configured (provider named and key present)
     * @param provider  the provider that answers: {@code template} (built in) or the language-model vendor
     * @param model     the model name; null for the template analyst
     * @param available AI endpoints answer; always true, because the template analyst needs nothing external
     */
    public record Ai(boolean enabled, String provider, String model, boolean available) {
    }
}
