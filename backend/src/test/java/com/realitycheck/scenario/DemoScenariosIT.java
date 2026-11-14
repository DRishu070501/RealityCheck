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
                "Add two polishing machines", "Increase production demand by 20%", "Make five employees unavailable",
                "Reduce cutting capacity by 30%", "Introduce an additional shift");
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

        JsonNode wheels = pool(validations.get("Add two polishing machines"), "Polishing wheels");
        assertThat(wheels.path("capacityBefore").asInt()).isEqualTo(6);
        assertThat(wheels.path("capacityAfter").asInt()).isEqualTo(8);
        // Wheels go to 8, but 12 polishers at two per wheel still allow only 6 slots.
        JsonNode polishingStage = stage(validations.get("Add two polishing machines"), "POLISHING");
        assertThat(polishingStage.path("maxSlotsBefore").asInt()).isEqualTo(6);
        assertThat(polishingStage.path("maxSlotsAfter").asInt()).isEqualTo(6);

        JsonNode saws = pool(validations.get("Reduce cutting capacity by 30%"), "Laser saws");
        assertThat(saws.path("capacityAfter").asInt()).isEqualTo(2); // 4 * 0.7 = 2.8, rounded down
        assertThat(stage(validations.get("Reduce cutting capacity by 30%"), "CUTTING").path("maxSlotsAfter").asInt())
                .isEqualTo(2);

        JsonNode polishers = pool(validations.get("Make five employees unavailable"), "Polishers");
        assertThat(polishers.path("capacityAfter").asInt()).isEqualTo(12);
        assertThat(polishers.path("windowsAdded").asInt()).isEqualTo(1);

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
        String wheelsScenario = ids.get("Add two polishing machines");

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
                .isEqualTo("Polishing wheels: capacity +2 units, 6 to 8 units");

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
        JsonNode wheelPool = resource(alternative, "Polishing wheels");
        assertThat(wheelPool.path("capacity").path("baseline").asText()).isEqualTo("6");
        assertThat(wheelPool.path("capacity").path("alternative").asText()).isEqualTo("8");
        assertThat(alternative.path("stages")).hasSize(8);
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
                {"payload": {"resourceId": "%s", "mode": "DELTA", "value": 3}}"""
                .formatted(wheelPool.path("resourceId").asText()));
        JsonNode again = post("/api/simulations/compare", """
                {"baselineRunId": "%s", "alternativeRunIds": ["%s"]}""".formatted(baselineRun, wheelsRun)).getBody();
        assertThat(again.path("alternatives").get(0).path("run").path("appliedChanges").get(0).path("summary").asText())
                .isEqualTo("Polishing wheels: capacity +2 units, 6 to 8 units");

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
