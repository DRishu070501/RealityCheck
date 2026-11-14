package com.realitycheck.common.system;

import java.time.Instant;

public record SystemInfoResponse(
        String application,
        String version,
        Instant serverTime,
        Database database,
        Migrations migrations,
        Ai ai) {

    /** {@code product} and {@code version} are null when the database cannot be reached. */
    public record Database(String status, String product, String version) {

        public static final String UP = "UP";
        public static final String DOWN = "DOWN";
    }

    /** {@code currentVersion} is null when no migration has been applied or the database is down. */
    public record Migrations(int appliedCount, String currentVersion) {
    }

    /** Whether AI features are switched on, and which provider. Never includes credentials. */
    public record Ai(boolean enabled, String provider) {
    }
}
