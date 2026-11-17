package com.realitycheck.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.demo.DemoSeeder;
import java.time.Instant;
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The seeded demonstration factory, simulated from the start of its history with every lot it received, has
 * the median lead times, with and without parking, of the real factory it was calibrated on (spec 11, task
 * 9): through the database, the snapshot and the API, not only the engine ({@code DemoCalibrationTest}).
 */
class DemoCalibrationIT extends ScenarioTestSupport {

    private static final Instant SEEDED_AT = Instant.parse("2026-01-21T06:00:00Z");
    /** 09:00 in Surat on Monday 5 January: the first lot arrives then, and nothing is in progress before. */
    private static final Instant HISTORY_START = Instant.parse("2026-01-05T03:30:00Z");
    private static final double DAY = 86_400.0;

    @Autowired
    private DemoSeeder seeder;

    @Override
    protected String createScenario(String name) {
        throw new UnsupportedOperationException("This test forks the seeded factory itself");
    }

    @Test
    void simulatingEveryRecordedLotGivesTheCalibratedLeadTimes() throws Exception {
        emptyAllTables();
        String demoFactory = seeder.seed(SEEDED_AT).toString();
        JsonNode calibration = new ObjectMapper().readTree(getClass().getResourceAsStream("/demo/calibration.json"));

        ResponseEntity<JsonNode> created = post("/api/scenarios", """
                {"factoryId": "%s", "name": "Every lot from the start", "forkAt": "%s", "includeFutureOrders": true}"""
                .formatted(demoFactory, HISTORY_START));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        String run = runToCompletion(created.getBody().path("id").asText());
        JsonNode results = get("/api/simulations/" + run + "/results").getBody();
        JsonNode summary = results.path("summary");

        assertThat(results.path("terminationReason").asText()).isEqualTo("COMPLETED");
        assertThat(summary.path("parkingSeconds").asLong()).isPositive();
        assertThat(summary.path("jobWorkSeconds").asLong()).isPositive();
        assertThat(summary.path("waitingStageSeconds").asLong()).isPositive();
        assertThat(summary.path("handlingSeconds").asLong()).isPositive();
        assertThat(summary.path("batchLeadMedianSeconds").asLong() / DAY).as("median lead days")
                .isCloseTo(calibration.path("leadDays").path("median").asDouble(), Percentage.withPercentage(15));
        assertThat(summary.path("batchManufacturingMedianSeconds").asLong() / DAY).as("median days without parking")
                .isCloseTo(calibration.path("manufacturingDays").path("median").asDouble(),
                        Percentage.withPercentage(15));
    }
}
