package com.realitycheck.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.demo.DemoSeeder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Backtests on the seeded demonstration factory (spec 11, task 10): each fork is simulated with no changes and
 * compared with recorded history over a window cut at the last recorded event; errors are finite and stored.
 */
class BacktestIT extends ScenarioTestSupport {

    private static final Instant SEEDED_AT = Instant.parse("2026-01-21T06:00:00Z");

    @Autowired
    private DemoSeeder seeder;

    @Override
    protected String createScenario(String name) {
        throw new UnsupportedOperationException("This test backtests the seeded factory itself");
    }

    @Test
    void comparesEachForkWithRecordedHistoryUpToTheLastEvent() {
        emptyAllTables();
        String factory = seeder.seed(SEEDED_AT).toString();

        ResponseEntity<JsonNode> created = post("/api/backtests", """
                {"factoryId": "%s"}""".formatted(factory));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        JsonNode backtest = created.getBody();
        JsonNode forks = backtest.path("forks");
        assertThat(forks).hasSize(4);

        Instant lastEvent = Instant.parse(get("/api/timeline/summary?factoryId=" + factory).getBody().path("lastEventAt").asText());
        assertThat(lastEvent).isBeforeOrEqualTo(SEEDED_AT);
        List<Instant> forkDates = new ArrayList<>();
        for (JsonNode fork : forks) {
            Instant forkAt = Instant.parse(fork.path("forkAt").asText());
            Instant until = Instant.parse(fork.path("comparedUntil").asText());
            forkDates.add(forkAt);
            assertThat(until).isAfter(forkAt).isBeforeOrEqualTo(lastEvent);
            if (fork.path("cutAtLastEvent").asBoolean()) {
                assertThat(until).isEqualTo(lastEvent);
            }
            assertThat(fork.path("batches").asInt()).isPositive();
            assertThat(fork.path("finished").path("recorded").asLong()).isPositive();
            assertThat(fork.path("finished").path("simulated").asLong()).isPositive();
            // Finite: every error is a number, never NaN or infinite, and present whenever recorded is not zero.
            assertThat(new java.math.BigDecimal(fork.path("finished").path("percent").asText())).isNotNull();
            assertThat(new java.math.BigDecimal(fork.path("medianLeadSeconds").path("percent").asText())).isNotNull();
            assertThat(fork.path("wip")).hasSize(11);
            int recordedWip = 0;
            int absolute = 0;
            for (JsonNode stage : fork.path("wip")) {
                recordedWip += stage.path("recorded").asInt();
                absolute += Math.abs(stage.path("simulated").asInt() - stage.path("recorded").asInt());
            }
            assertThat(fork.path("wipAbsoluteError").asInt()).isEqualTo(absolute);
            assertThat(recordedWip).isPositive();
            assertThat(new java.math.BigDecimal(fork.path("wipError").asText()).doubleValue())
                    .isCloseTo((double) absolute / recordedWip, org.assertj.core.data.Offset.offset(1e-5));
        }
        assertThat(forkDates).isSorted().doesNotHaveDuplicates();
        assertThat(forks.get(forks.size() - 1).path("cutAtLastEvent").asBoolean()).isTrue();
        assertThat(backtest.path("overall").path("forks").asInt()).isEqualTo(4);
        assertThat(backtest.path("overall").path("finishedError").isTextual()).isTrue();

        // Stored as computed, and the latest for the factory.
        String id = backtest.path("id").asText();
        assertThat(get("/api/backtests/" + id).getBody()).isEqualTo(backtest);
        JsonNode latest = get("/api/backtests?factoryId=" + factory).getBody();
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0)).isEqualTo(backtest);

        // A date outside the recorded history is refused.
        ResponseEntity<JsonNode> outside = post("/api/backtests", """
                {"factoryId": "%s", "forkAts": ["%s"]}""".formatted(factory, SEEDED_AT.plusSeconds(86_400)));
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // A chosen date gives one fork at that date.
        ResponseEntity<JsonNode> chosen = post("/api/backtests", """
                {"factoryId": "%s", "forkAts": ["%s"]}""".formatted(factory, forkDates.get(1)));
        assertThat(chosen.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(chosen.getBody().path("forks").get(0).path("forkAt").asText())
                .isEqualTo(forks.get(1).path("forkAt").asText());
        assertThat(chosen.getBody().path("forks").get(0)).isEqualTo(forks.get(1));
    }

    @Test
    void anUnknownFactoryIsNotFound() {
        ResponseEntity<JsonNode> response = post("/api/backtests", """
                {"factoryId": "00000000-0000-0000-0000-000000000099"}""");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
