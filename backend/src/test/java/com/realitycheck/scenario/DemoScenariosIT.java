package com.realitycheck.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.demo.DemoSeeder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The six preconfigured scenarios on the seeded factory, and comparisons between their runs
 * (spec 06 requirement 7, spec 08). Seeding is slow, so each test checks a whole flow.
 */
class DemoScenariosIT extends ScenarioTestSupport {

    /** Wednesday of the third week, 11:30 in Surat. Five working days earlier is Thursday 15 January, 11:30. */
    private static final Instant SEEDED_AT = Instant.parse("2026-01-21T06:00:00Z");
    private static final Instant DEMO_FORK = Instant.parse("2026-01-15T06:00:00Z");

    @Autowired
    private DemoSeeder seeder;

    @Override
    protected String createScenario(String name) {
        throw new UnsupportedOperationException("The demonstration scenarios are seeded");
    }

    @Test
    void theSixScenariosShareOneForkValidateAndHaveNoResultsYet() {
        emptyAllTables();
        String demoFactory = seeder.seed(SEEDED_AT).toString();

        JsonNode list = get("/api/scenarios?factoryId=" + demoFactory).getBody();

        assertThat(texts(list.path("items"), "name")).containsExactly("Current factory operations",
                "Park half as long", "Three more inspectors", "Job worker 30% slower",
                "Increase production demand by 20%", "Introduce an additional shift");
        assertThat(texts(list.path("items"), "forkAt")).containsOnly(DEMO_FORK.toString());
        assertThat(texts(list.path("items"), "changeCount")).containsExactly("0", "1", "1", "1", "1", "1");
        assertThat(list.path("items").get(0).path("baseline").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from simulation_run", Long.class)).isZero();

        Map<String, JsonNode> validations = new HashMap<>();
        for (JsonNode item : list.path("items")) {
            JsonNode validation = post("/api/scenarios/" + item.path("id").asText() + "/validate", "").getBody();
            assertThat(validation.path("valid").asBoolean()).as(item.path("name") + " " + validation).isTrue();
            assertThat(validation.path("warnings")).as(item.path("name").asText()).isEmpty();
            validations.put(item.path("name").asText(), validation);
        }
        assertThat(validations.values().stream().map(v -> v.path("baseStateHash").asText()).distinct()).hasSize(1);
        assertThat(validations.values().stream().map(v -> v.path("inputHash").asText()).distinct()).hasSize(6);

        JsonNode inspectors = pool(validations.get("Three more inspectors"), "Inspectors");
        assertThat(inspectors.path("capacityBefore").asInt()).isEqualTo(3);
        assertThat(inspectors.path("capacityAfter").asInt()).isEqualTo(6);
        assertThat(stage(validations.get("Three more inspectors"), "INSPECTION").path("maxSlotsAfter").asInt())
                .isEqualTo(6);

        assertThat(changeSummary(list, "Park half as long")).isEqualTo("Rough store (parked): time held -50%");
        assertThat(changeSummary(list, "Job worker 30% slower")).isEqualTo("Outside job work: time held +30%");

        JsonNode demand = validations.get("Increase production demand by 20%").path("preview");
        assertThat(new BigDecimal(demand.path("unstartedStonesAfter").asText())).isEqualByComparingTo(
                new BigDecimal(demand.path("unstartedStonesBefore").asText()).multiply(new BigDecimal("1.2")));

        JsonNode shift = validations.get("Introduce an additional shift").path("preview");
        assertThat(shift.path("weeklyWorkingSecondsBefore").asLong()).isEqualTo(6 * 8 * 3600);
        assertThat(shift.path("weeklyWorkingSecondsAfter").asLong()).isEqualTo(6 * 12 * 3600);
    }

    @Test
    void comparesTheBaselineWithAnAlternativeFromStoredResultsOnly() {
        emptyAllTables();
        String demoFactory = seeder.seed(SEEDED_AT).toString();
        Map<String, String> ids = new HashMap<>();
        for (JsonNode item : get("/api/scenarios?factoryId=" + demoFactory).getBody().path("items")) {
            ids.put(item.path("name").asText(), item.path("id").asText());
        }
        String baselineScenario = ids.get("Current factory operations");
        String wheelsScenario = ids.get("Three more inspectors");

        String wheelsRun = runToCompletion(wheelsScenario);
        JsonNode missing = get("/api/scenarios/" + wheelsScenario + "/comparisons").getBody();
        assertThat(missing.path("baselineMissing").asBoolean()).isTrue();
        assertThat(missing.path("suggestedBaselineScenarioId").asText()).isEqualTo(baselineScenario);
        assertThat(missing.path("items")).isEmpty();

        String baselineRun = runToCompletion(baselineScenario);
        ResponseEntity<JsonNode> compared = post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": ["%s"]}""".formatted(baselineRun, wheelsRun));

        assertThat(compared.getStatusCode()).as(String.valueOf(compared.getBody())).isEqualTo(HttpStatus.OK);
        JsonNode comparison = compared.getBody();
        assertThat(comparison.path("source").asText()).isEqualTo("SIMULATED");
        assertThat(comparison.path("sameStartingState").asBoolean()).isTrue();
        assertThat(comparison.path("forkAt").asText()).isEqualTo(DEMO_FORK.toString());
        assertThat(comparison.path("baseline").path("scenarioName").asText()).isEqualTo("Current factory operations");
        assertThat(comparison.path("baseline").path("appliedChanges")).isEmpty();
        JsonNode alternative = comparison.path("alternatives").get(0);
        assertThat(alternative.path("run").path("appliedChanges").get(0).path("summary").asText())
                .isEqualTo("Inspectors: capacity +3 units, 3 to 6 units");

        // Every value is the stored one: compare against each run's own results.
        JsonNode baselineResults = get("/api/simulations/" + baselineRun + "/results").getBody();
        JsonNode wheelsResults = get("/api/simulations/" + wheelsRun + "/results").getBody();
        JsonNode elapsed = metric(alternative, "elapsedSeconds");
        assertThat(elapsed.path("baseline").asText()).isEqualTo(baselineResults.path("summary").path("elapsedSeconds").asText());
        assertThat(elapsed.path("alternative").asText()).isEqualTo(wheelsResults.path("summary").path("elapsedSeconds").asText());
        JsonNode cost = metric(alternative, "cost.total");
        assertThat(cost.path("baseline").asText()).isEqualTo(baselineResults.path("summary").path("cost").path("total").asText());
        assertThat(cost.path("alternative").asText()).isEqualTo(wheelsResults.path("summary").path("cost").path("total").asText());
        assertThat(new BigDecimal(cost.path("absolute").asText())).isEqualByComparingTo(
                new BigDecimal(cost.path("alternative").asText()).subtract(new BigDecimal(cost.path("baseline").asText())));
        JsonNode wheelPool = resource(alternative, "Inspectors");
        assertThat(wheelPool.path("capacity").path("baseline").asText()).isEqualTo("3");
        assertThat(wheelPool.path("capacity").path("alternative").asText()).isEqualTo("6");
        assertThat(alternative.path("stages")).hasSize(11);
        assertThat(alternative.path("orders").size()).isEqualTo(baselineResults.path("orders").size());
        assertThat(alternative.path("bottleneck").path("change").asText()).isIn("SAME", "MOVED", "RESOLVED");

        // The scenario's page finds the baseline automatically.
        JsonNode found = get("/api/scenarios/" + wheelsScenario + "/comparisons").getBody();
        assertThat(found.path("baselineMissing").asBoolean()).isFalse();
        assertThat(found.path("items")).hasSize(1);
        assertThat(found.path("items").get(0).path("baseline").path("runId").asText()).isEqualTo(baselineRun);
        assertThat(get("/api/scenarios/" + baselineScenario + "/comparisons").getBody().path("items")).isEmpty();

        // Editing the scenario afterwards does not change what the comparison says was applied.
        String changeId = get("/api/scenarios/" + wheelsScenario).getBody().path("changes").get(0).path("id").asText();
        put("/api/scenarios/" + wheelsScenario + "/changes/" + changeId, """
                {"payload": {"resourceId": "%s", "mode": "DELTA", "value": 4}}"""
                .formatted(wheelPool.path("resourceId").asText()));
        JsonNode again = post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": ["%s"]}""".formatted(baselineRun, wheelsRun)).getBody();
        assertThat(again.path("alternatives").get(0).path("run").path("appliedChanges").get(0).path("summary").asText())
                .isEqualTo("Inspectors: capacity +3 units, 3 to 6 units");

        // A run from another starting point needs explicit consent.
        ResponseEntity<JsonNode> later = post("/api/scenarios", """
                {"factoryId": "%s", "name": "Baseline a day later", "forkAt": "%s"}"""
                .formatted(demoFactory, DEMO_FORK.plusSeconds(86_400)));
        String laterRun = runToCompletion(later.getBody().path("id").asText());
        ResponseEntity<JsonNode> refused = post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": ["%s"]}""".formatted(baselineRun, laterRun));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().path("code").asText()).isEqualTo("DIFFERENT_STARTING_STATE");
        JsonNode allowed = post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": ["%s"], "allowDifferentBaseline": true}"""
                .formatted(baselineRun, laterRun)).getBody();
        assertThat(allowed.path("sameStartingState").asBoolean()).isFalse();
        assertThat(texts(allowed.path("warnings"), "code")).contains("DIFFERENT_STARTING_STATE");

        // Request checks.
        assertThat(post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": ["%s"]}""".formatted(baselineRun, baselineRun))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": []}""".formatted(baselineRun))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": ["00000000-0000-0000-0000-000000000009"]}"""
                .formatted(baselineRun)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void showsWhatRecordedHistorySaysHappenedAfterTheForkBesideTheSimulation() {
        emptyAllTables();
        String demoFactory = seeder.seed(SEEDED_AT).toString();
        Map<String, String> ids = new HashMap<>();
        for (JsonNode item : get("/api/scenarios?factoryId=" + demoFactory).getBody().path("items")) {
            ids.put(item.path("name").asText(), item.path("id").asText());
        }
        String baselineRun = runToCompletion(ids.get("Current factory operations"));
        String shiftRun = runToCompletion(ids.get("Introduce an additional shift"));
        String history = fingerprint("operational_event", "production_order", "production_batch");

        JsonNode withoutActual = post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": ["%s"]}""".formatted(baselineRun, shiftRun)).getBody();
        assertThat(withoutActual.path("actual").isNull()).isTrue();

        ResponseEntity<JsonNode> response = post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": ["%s"], "includeActual": true}"""
                .formatted(baselineRun, shiftRun));
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        JsonNode actual = response.getBody().path("actual");
        // The simulated part is unchanged by asking for the recorded one.
        assertThat(response.getBody().path("alternatives")).isEqualTo(withoutActual.path("alternatives"));

        assertThat(actual.path("source").asText()).isEqualTo("ACTUAL");
        Instant lastEvent = jdbc.queryForObject(
                "select max(occurred_at) from operational_event where factory_id = ?::uuid", Instant.class, demoFactory);
        assertThat(Instant.parse(actual.path("asOf").asText())).isEqualTo(lastEvent);

        // Scope: exactly the orders in the baseline run.
        JsonNode simulatedOrders = get("/api/simulations/" + baselineRun + "/results").getBody().path("orders");
        assertThat(actual.path("ordersInScope").asInt()).isEqualTo(simulatedOrders.size());
        assertThat(texts(actual.path("orders"), "orderNumber"))
                .containsExactlyElementsOf(texts(simulatedOrders, "orderNumber"));

        // Completion times equal what the order projection recorded, independently of the service.
        int completed = 0;
        for (JsonNode order : actual.path("orders")) {
            Instant recorded = jdbc.queryForObject("select completed_at from production_order where id = ?::uuid",
                    Instant.class, order.path("orderId").asText());
            if (order.path("completedAt").isNull()) {
                assertThat(recorded).as(order.path("orderNumber").asText()).isNull();
            } else {
                completed++;
                assertThat(Instant.parse(order.path("completedAt").asText()))
                        .as(order.path("orderNumber").asText()).isEqualTo(recorded);
            }
        }
        assertThat(actual.path("ordersCompleted").asInt()).isEqualTo(completed).isPositive();

        // Output: completions after the fork at the last stage of each batch's route (Recut for Tops, Dispatch
        // for Full), by batches of those orders (no correction touches them).
        String scope = """
                from operational_event e
                join production_batch b on b.id = e.batch_id
                where e.event_type = 'STAGE_COMPLETED' and e.occurred_at > ?::timestamptz
                  and e.stage_id = (select s.id from workflow_stage s join workflow w on w.id = s.workflow_id
                                    where w.factory_id = ?::uuid and w.status = 'ACTIVE'
                                      and s.code = case b.route_code when 'TOPS' then 'RECUT' else 'DISPATCH_WAIT' end)
                  and b.order_id in (select (o ->> 'id')::uuid
                                     from simulation_run r, jsonb_array_elements(r.input_snapshot -> 'orders') o
                                     where r.id = ?::uuid)""";
        assertThat(jdbc.queryForObject("select count(*) from operational_event c where c.corrects_event_id in"
                + " (select e.id " + scope + ")", Long.class, DEMO_FORK.toString(), demoFactory, baselineRun)).isZero();
        BigDecimal stones = jdbc.queryForObject("select coalesce(sum(e.stone_quantity), 0) " + scope,
                BigDecimal.class, DEMO_FORK.toString(), demoFactory, baselineRun);
        BigDecimal carats = jdbc.queryForObject("select coalesce(sum(e.carat_quantity), 0) " + scope,
                BigDecimal.class, DEMO_FORK.toString(), demoFactory, baselineRun);
        assertThat(new BigDecimal(actual.path("outputStones").asText())).isEqualByComparingTo(stones).isPositive();
        assertThat(new BigDecimal(actual.path("outputCarats").asText())).isEqualByComparingTo(carats);

        assertThat(fingerprint("operational_event", "production_order", "production_batch")).isEqualTo(history);
    }

    private String changeSummary(JsonNode list, String scenario) {
        for (JsonNode item : list.path("items")) {
            if (item.path("name").asText().equals(scenario)) {
                return get("/api/scenarios/" + item.path("id").asText()).getBody()
                        .path("changes").get(0).path("summary").asText();
            }
        }
        throw new AssertionError("No scenario " + scenario);
    }

    private static JsonNode pool(JsonNode validation, String name) {
        for (JsonNode pool : validation.path("preview").path("pools")) {
            if (pool.path("name").asText().equals(name)) {
                return pool;
            }
        }
        throw new AssertionError("No pool " + name);
    }

    private static JsonNode stage(JsonNode validation, String code) {
        for (JsonNode stage : validation.path("preview").path("stages")) {
            if (stage.path("code").asText().equals(code)) {
                return stage;
            }
        }
        throw new AssertionError("No stage " + code);
    }

    private static JsonNode metric(JsonNode alternative, String key) {
        for (JsonNode metric : alternative.path("metrics")) {
            if (metric.path("key").asText().equals(key)) {
                return metric;
            }
        }
        throw new AssertionError("No metric " + key);
    }

    private static JsonNode resource(JsonNode alternative, String name) {
        for (JsonNode resource : alternative.path("resources")) {
            if (resource.path("name").asText().equals(name)) {
                return resource;
            }
        }
        throw new AssertionError("No resource " + name);
    }
}
