package com.realitycheck.ai.service;

import com.realitycheck.common.config.RealityCheckProperties;
import com.realitycheck.common.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * At most {@code realitycheck.ai.requests-per-minute} AI requests per calendar minute, across all
 * callers, held in memory. It bounds what a language model can cost; a restart resets it.
 */
@Component
public class AiRateLimiter {

    private final int limit;
    private final Clock clock;
    private long window = Long.MIN_VALUE;
    private int used;

    public AiRateLimiter(RealityCheckProperties properties, Clock clock) {
        this.limit = Math.max(1, properties.ai().requestsPerMinute());
        this.clock = clock;
    }

    /** @throws ApiException 429 with {@code Retry-After} when this minute's allowance is used up */
    public synchronized void acquire() {
        Instant now = clock.instant();
        long minute = now.getEpochSecond() / 60;
        if (minute != window) {
            window = minute;
            used = 0;
        }
        if (used >= limit) {
            long retryAfter = Math.max(1, Duration.between(now, Instant.ofEpochSecond((minute + 1) * 60)).toSeconds());
            throw ApiException.tooManyRequests("Too many AI requests this minute (limit " + limit
                    + "). Try again shortly.").withHeader("Retry-After", Long.toString(retryAfter));
        }
        used++;
    }
}
