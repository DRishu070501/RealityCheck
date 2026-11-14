package com.realitycheck.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exists only when {@code realitycheck.demo.reset-enabled=true}. It erases everything, so it must
 * stay off in any deployment that holds data someone wants to keep.
 */
@RestController
@RequestMapping(path = "/api/demo", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "realitycheck.demo", name = "reset-enabled", havingValue = "true")
@Tag(name = "Demo")
public class DemoController {

    public record DemoResetResponse(UUID factoryId, Instant resetAt) {
    }

    private final DemoSeeder seeder;
    private final Clock clock;

    public DemoController(DemoSeeder seeder, Clock clock) {
        this.seeder = seeder;
        this.clock = clock;
    }

    @PostMapping("/reset")
    @Operation(summary = "Erase all data and re-create the demonstration factory")
    public DemoResetResponse reset() {
        Instant now = clock.instant();
        return new DemoResetResponse(seeder.reset(now), now);
    }
}
