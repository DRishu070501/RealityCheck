package com.realitycheck.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** The scheduled reset of a public deployment (spec 11, task 15). The schedule itself never fires here. */
@TestPropertySource(properties = {"realitycheck.demo.reset-cron=0 0 0 1 1 *", "realitycheck.demo.reset-enabled=false"})
class DemoNightlyResetIT extends IntegrationTest {

    private static final Instant SEEDED_AT = Instant.parse("2026-01-21T06:00:00Z");

    @Autowired
    private DemoSeeder seeder;

    @Autowired
    private DemoNightlyReset nightly;

    @Test
    void restoresTheDemonstrationButNeverErasesAnotherFactory() {
        seeder.reset(SEEDED_AT);
        String before = jdbc.queryForObject("select max(created_at)::text from factory", String.class);

        assertThat(nightly.resetIfDemoOnly()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from factory where demo", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select max(created_at)::text from factory", String.class)).isNotEqualTo(before);

        String other = createFactory("Someone's real factory");
        assertThat(nightly.resetIfDemoOnly()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from factory where id = ?::uuid", Integer.class, other))
                .isEqualTo(1);
        // The reset endpoint stays off: only the schedule resets.
        assertThat(post("/api/demo/reset", "{}").getStatusCode().value()).isEqualTo(404);
    }
}
