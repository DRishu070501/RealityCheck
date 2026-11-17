package com.realitycheck.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.IntegrationTest;
import com.realitycheck.demo.DemoSeeder;
import java.math.BigDecimal;
import java.util.List;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The engine run on the seeded demonstration factory, through the API, against PostgreSQL. */
class BaselineSimulationIT extends IntegrationTest {

    /** Wednesday of the third week, 11:30 in Surat; the seeded history starts Monday 5 January, 09:00. */
    private static final Instant SEEDED_AT = Instant.parse("2026-01-21T06:00:00Z");

    @Autowired
    private DemoSeeder seeder;

    @Test
    void simulatesTheFactoryForwardFromItsRecordedStateUntilAllWorkIsDone() {
        String factoryId = seeder.seed(SEEDED_AT).toString();
        JsonNode state = get("/api/factories/" + factoryId + "/state?at=" + SEEDED_AT).getBody();
        long openOrders = state.path("orders").path("IN_PROGRESS").asLong() + state.path("orders").path("PENDING").asLong();

        JsonNode result = simulate(factoryId, SEEDED_AT).getBody();

        assertThat(result.path("source").asText()).isEqualTo("SIMULATED");
        assertThat(result.path("algorithmVersion").asText())
                .isEqualTo(com.realitycheck.simulation.engine.SimulationEngine.ALGORITHM_VERSION);
        assertThat(result.path("forkAt").asText()).isEqualTo(SEEDED_AT.toString());
        assertThat(result.path("terminationReason").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("unfinished")).isEmpty();
        JsonNode summary = result.path("summary");
        // Exactly the orders that were open at the fork are simulated, and all of them finish.
        assertThat(summary.path("ordersTotal").asLong()).isEqualTo(openOrders).isPositive();
        assertThat(summary.path("ordersCompleted").asLong()).isEqualTo(openOrders);
        assertThat(Instant.parse(result.path("endsAt").asText())).isAfter(SEEDED_AT);
        assertThat(summary.path("elapsedSeconds").asLong()).isGreaterThan(summary.path("workingSeconds").asLong());
        assertThat(summary.path("cost").path("currencyCode").asText()).isEqualTo("INR");
        assertThat(new BigDecimal(summary.path("cost").path("total").asText())).isPositive();
        assertThat(new BigDecimal(summary.path("outputStones").asText()))
                .isLessThan(new BigDecimal(summary.path("inputStones").asText())).isPositive();
    }

    @Test
    void givesABottleneckVerdictBackedByTheEvidenceOfEveryStage() {
        String factoryId = seeder.seed(SEEDED_AT).toString();

        JsonNode result = simulate(factoryId, SEEDED_AT).getBody();
        JsonNode bottleneck = result.path("bottleneck");
        String reason = bottleneck.path("reasonCode").asText();

        // The calibrated factory's packets wait mostly for measured handling and holding times, not for
        // capacity, so whether one stage qualifies is the engine's finding, not something to assume here.
        assertThat(reason).isIn("CONSTRAINED_POOL_WITH_QUEUE", "NO_SUSTAINED_QUEUE", "NO_CLEAR_BOTTLENECK");
        assertThat(bottleneck.path("explanation").asText()).isNotBlank();
        BigDecimal highest = BigDecimal.ZERO;
        JsonNode top = null;
        for (JsonNode stage : result.path("stages")) {
            BigDecimal score = new BigDecimal(stage.path("bottleneck").path("score").asText());
            if (score.compareTo(highest) > 0) {
                highest = score;
                top = stage;
            }
        }
        assertThat(top).as("some stage has a score").isNotNull();
        if (reason.equals("CONSTRAINED_POOL_WITH_QUEUE")) {
            // The named stage is the highest-scoring one and work waited behind its full slots.
            assertThat(bottleneck.path("stageCode").asText()).isEqualTo(top.path("code").asText());
            assertThat(bottleneck.path("explanation").asText())
                    .contains("is the bottleneck: work waited behind full slots for");
            assertThat(new BigDecimal(top.path("bottleneck").path("saturation").asText()))
                    .isGreaterThan(new BigDecimal("0.5"));
            assertThat(top.path("maxQueueLength").asInt()).isPositive();
        } else {
            assertThat(bottleneck.path("stageCode").isNull()).as(reason).isTrue();
        }
        // Holding stages use no capacity, so they are never scored as constrained.
        for (JsonNode stage : result.path("stages")) {
            if (List.of("PARKING", "JOB_WORK", "DISPATCH_WAIT").contains(stage.path("code").asText())) {
                assertThat(new BigDecimal(stage.path("bottleneck").path("score").asText()))
                        .as(stage.path("code").asText()).isZero();
            }
        }
    }

    @Test
    void conservesStonesAndCaratsAtEveryStageAndStartsFromWhatBatchesHeldAtTheFork() {
        String factoryId = seeder.seed(SEEDED_AT).toString();
        JsonNode state = get("/api/factories/" + factoryId + "/state?at=" + SEEDED_AT).getBody();
        BigDecimal heldAtFork = BigDecimal.ZERO;
        for (JsonNode batch : state.path("batches")) {
            if (!batch.path("status").asText().equals("COMPLETED")) {
                heldAtFork = heldAtFork.add(new BigDecimal(batch.path("inputStones").asText()));
            }
        }

        JsonNode result = simulate(factoryId, SEEDED_AT).getBody();

        assertThat(new BigDecimal(result.path("summary").path("inputStones").asText()))
                .isEqualByComparingTo(heldAtFork);
        for (JsonNode stage : result.path("stages")) {
            assertThat(new BigDecimal(stage.path("outputStones").asText())
                    .add(new BigDecimal(stage.path("lossStones").asText())))
                    .as(stage.path("code").asText())
                    .isEqualByComparingTo(stage.path("inputStones").asText());
            assertThat(new BigDecimal(stage.path("outputCarats").asText())
                    .add(new BigDecimal(stage.path("lossCarats").asText())))
                    .isEqualByComparingTo(stage.path("inputCarats").asText());
        }
        for (JsonNode pool : result.path("pools")) {
            if (!pool.path("utilization").isNull()) {
                assertThat(new BigDecimal(pool.path("utilization").asText()))
                        .as(pool.path("name").asText()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
            }
        }
    }

    @Test
    void givesTheSameAnswerEveryTimeAndLeavesTheRecordedHistoryUntouched() {
        String factoryId = seeder.seed(SEEDED_AT).toString();
        String historyBefore = historyFingerprint();

        JsonNode first = simulate(factoryId, SEEDED_AT).getBody();
        JsonNode second = simulate(factoryId, SEEDED_AT).getBody();

        assertThat(second).isEqualTo(first);
        assertThat(historyFingerprint()).isEqualTo(historyBefore);
    }

    @Test
    void forkingInThePastBringsInTheOrdersThatWerePlacedAfterwards() {
        String factoryId = seeder.seed(SEEDED_AT).toString();
        Instant firstFriday = Instant.parse("2026-01-09T06:00:00Z");
        long allOrders = get("/api/orders?size=1").getBody().path("totalItems").asLong();
        JsonNode stateThen = get("/api/factories/" + factoryId + "/state?at=" + firstFriday).getBody();
        long finishedByThen = stateThen.path("orders").path("COMPLETED").asLong()
                + stateThen.path("orders").path("DISPATCHED").asLong();

        JsonNode result = simulate(factoryId, firstFriday).getBody();

        // Everything not yet finished on that Friday, including orders placed in the weeks after it.
        assertThat(result.path("summary").path("ordersTotal").asLong()).isEqualTo(allOrders - finishedByThen);
        assertThat(result.path("terminationReason").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("forkAt").asText()).isEqualTo(firstFriday.toString());
    }

    @Test
    void forkingBeforeAnyHistorySimulatesEveryOrderAsItArrives() {
        String factoryId = seeder.seed(SEEDED_AT).toString();
        long allOrders = get("/api/orders?size=1").getBody().path("totalItems").asLong();

        JsonNode result = simulate(factoryId, Instant.parse("2026-01-01T00:00:00Z")).getBody();

        assertThat(result.path("summary").path("ordersTotal").asLong()).isEqualTo(allOrders);
        assertThat(result.path("summary").path("ordersCompleted").asLong()).isEqualTo(allOrders);
    }

    @Test
    void aFactoryWithNoWorkHasNothingToSimulate() {
        String factoryId = createFactory("Idle Works");

        JsonNode result = simulate(factoryId, Instant.now().minus(1, ChronoUnit.HOURS)).getBody();

        assertThat(result.path("terminationReason").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("summary").path("processingSeconds").asLong()).isZero();
        assertThat(result.path("summary").path("stoneYield").isNull()).isTrue();
        assertThat(result.path("bottleneck").path("stageId").isNull()).isTrue();
        assertThat(result.path("bottleneck").path("reasonCode").asText()).isEqualTo("NO_WORK");
        assertThat(result.path("bottleneck").path("explanation").asText()).contains("No work was processed");
    }

    @Test
    void rejectsAForkInTheFutureAndAnUnknownFactory() {
        String factoryId = createFactory("Works");

        ResponseEntity<JsonNode> future = simulate(factoryId, Instant.now().plus(1, ChronoUnit.DAYS));
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorFields(future)).containsExactly("at");
        assertThat(get("/api/factories/00000000-0000-0000-0000-000000000001/baseline-simulation").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<JsonNode> simulate(String factoryId, Instant at) {
        return get("/api/factories/" + factoryId + "/baseline-simulation?at=" + at);
    }

    private String historyFingerprint() {
        return jdbc.queryForObject("""
                select md5(
                  (select string_agg(e::text, '|' order by e.seq) from operational_event e) ||
                  (select string_agg(b::text, '|' order by b.id) from production_batch b) ||
                  (select string_agg(o::text, '|' order by o.id) from production_order o))""", String.class);
    }
}
