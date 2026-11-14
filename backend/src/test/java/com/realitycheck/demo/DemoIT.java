package com.realitycheck.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.IntegrationTest;
import com.realitycheck.common.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "realitycheck.demo.reset-enabled=true")
class DemoIT extends IntegrationTest {

    /** Wednesday of the third week, 11:30 in Surat; the history therefore starts Monday 5 January, 09:00. */
    private static final Instant SEEDED_AT = Instant.parse("2026-01-21T06:00:00Z");

    private static final List<String> TABLES = List.of("factory", "working_shift", "calendar_break", "resource",
            "resource_availability", "workflow", "workflow_stage", "stage_requirement", "production_order",
            "production_batch", "operational_event");

    @Autowired
    private DemoSeeder seeder;

    @Test
    void seedsOneFullyConfiguredFactory() {
        String factoryId = seeder.seed(SEEDED_AT).toString();

        JsonNode factory = get("/api/factories/" + factoryId).getBody();
        assertThat(factory.path("name").asText()).isEqualTo("Surat Diamond Works");
        assertThat(factory.path("demo").asBoolean()).isTrue();
        assertThat(factory.path("description").asText()).contains("Fictional", "synthetic");
        assertThat(factory.path("createdAt").asText()).isEqualTo("2026-01-05T03:30:00Z");
        assertThat(factory.path("counts").path("resources").asLong()).isEqualTo(14);
        assertThat(factory.path("counts").path("workflows").asLong()).isEqualTo(1);
        // Monday to Saturday, nine hours less a one-hour break.
        assertThat(texts(factory.path("calendar").path("shifts"), "dayOfWeek"))
                .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(factory.path("calendar").path("weeklyWorkingSeconds").asLong()).isEqualTo(6 * 8 * 3600);
    }

    @Test
    void seedsTheEightStagesInOrderEachWithPoolsThatCanRunIt() {
        seeder.seed(SEEDED_AT);

        JsonNode workflows = get("/api/workflows").getBody().path("items");
        assertThat(workflows).hasSize(1);
        assertThat(workflows.get(0).path("status").asText()).isEqualTo("ACTIVE");
        JsonNode stages = get("/api/workflows/" + workflows.get(0).path("id").asText() + "/stages")
                .getBody().path("items");

        assertThat(texts(stages, "code")).containsExactly("ROUGH_INTAKE", "PLANNING", "MARKING", "CUTTING",
                "BRUTING", "POLISHING", "QUALITY_INSPECTION", "DISPATCH");
        assertThat(texts(stages, "sequence")).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        for (JsonNode stage : stages) {
            assertThat(stage.path("requirements")).as(stage.path("code").asText()).isNotEmpty();
            assertThat(stage.path("maxSlots").asInt()).as(stage.path("code").asText()).isGreaterThanOrEqualTo(1);
        }
        JsonNode polishing = stages.get(5);
        assertThat(texts(polishing.path("requirements"), "resourceName"))
                .containsExactly("Polishing wheels", "Polishers");
        assertThat(texts(polishing.path("requirements"), "unitsPerSlot")).containsExactly("1", "2");
        assertThat(polishing.path("maxSlots").asInt()).isEqualTo(6);
    }

    @Test
    void seedsMachineAndEmployeePoolsAndOneDowntimeWindow() {
        seeder.seed(SEEDED_AT);

        JsonNode machines = get("/api/resources?kind=MACHINE").getBody();
        JsonNode employees = get("/api/resources?kind=EMPLOYEE").getBody();
        assertThat(machines.path("totalItems").asLong()).isEqualTo(6);
        assertThat(employees.path("totalItems").asLong()).isEqualTo(8);
        for (JsonNode pool : machines.path("items")) {
            assertThat(pool.path("requiredByStages")).as(pool.path("name").asText()).isNotEmpty();
        }

        String wheels = idOf(machines.path("items"), "Polishing wheels");
        JsonNode availability = get("/api/resources/" + wheels + "/availability").getBody();
        assertThat(availability.path("windows")).hasSize(1);
        assertThat(availability.path("windows").get(0).path("capacityDelta").asInt()).isEqualTo(-2);
        assertThat(availability.path("windows").get(0).path("startsAt").asText()).isEqualTo("2026-01-07T03:30:00Z");
        assertThat(availability.path("windows").get(0).path("endsAt").asText()).isEqualTo("2026-01-07T12:30:00Z");
        assertThat(texts(availability.path("timeline"), "capacity")).containsExactly("4");
    }

    @Test
    void seedsOrdersInEveryStateWithAHistoryThatEndsAtTheSeedingInstant() {
        String factoryId = seeder.seed(SEEDED_AT).toString();

        assertThat(get("/api/orders?size=1").getBody().path("totalItems").asLong()).isGreaterThanOrEqualTo(30);
        for (String status : List.of("IN_PROGRESS", "COMPLETED", "DISPATCHED")) {
            assertThat(get("/api/orders?size=1&status=" + status).getBody().path("totalItems").asLong())
                    .as(status).isPositive();
        }
        assertThat(get("/api/events?type=CORRECTION").getBody().path("totalItems").asLong()).isEqualTo(1);
        assertThat(get("/api/events?type=RESOURCE_UNAVAILABLE").getBody().path("totalItems").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select max(occurred_at) <= ?::timestamptz from operational_event",
                Boolean.class, SEEDED_AT.toString())).isTrue();

        JsonNode summary = get("/api/timeline/summary").getBody();
        assertThat(summary.path("firstEventAt").asText()).isEqualTo("2026-01-05T03:30:00Z");
        assertThat(summary.path("buckets").size()).isGreaterThanOrEqualTo(14);
        assertThat(get("/api/factories/" + factoryId).getBody().path("counts").path("orders").asLong())
                .isGreaterThanOrEqualTo(30);
    }

    @Test
    void theStoredPositionOfEveryBatchMatchesAReplayOfItsHistory() {
        String factoryId = seeder.seed(SEEDED_AT).toString();

        JsonNode state = get("/api/factories/" + factoryId + "/state?at=" + SEEDED_AT).getBody();

        assertThat(state.path("batches").size())
                .isEqualTo(jdbc.queryForObject("select count(*) from production_batch", Integer.class));
        for (JsonNode batch : state.path("batches")) {
            Map<String, Object> stored = jdbc.queryForMap(
                    "select status, current_stage_id::text as stage, current_stones::text as stones "
                            + "from production_batch where id = ?::uuid", batch.path("batchId").asText());
            assertThat(batch.path("status").asText()).isEqualTo(stored.get("status"));
            assertThat(batch.path("stageId").isNull() ? null : batch.path("stageId").asText())
                    .isEqualTo(stored.get("stage"));
            assertThat(batch.path("inputStones").asText()).isEqualTo(stored.get("stones"));
        }
        JsonNode polishing = state.path("stages").get(5);
        assertThat(polishing.path("code").asText()).isEqualTo("POLISHING");
        assertThat(polishing.path("inProgress").asInt()).isEqualTo(6);
        assertThat(polishing.path("queued").asInt()).as("work waits at polishing").isPositive();
    }

    @Test
    void theSeededHistoryNeverUsesMoreUnitsThanAPoolHas() {
        String factoryId = seeder.seed(SEEDED_AT).toString();

        for (Instant at = Instant.parse("2026-01-05T04:00:00Z"); at.isBefore(SEEDED_AT);
                at = at.plus(5, java.time.temporal.ChronoUnit.HOURS)) {
            for (JsonNode pool : get("/api/factories/" + factoryId + "/state?at=" + at).getBody().path("resources")) {
                assertThat(pool.path("unitsInUse").asInt())
                        .as(pool.path("name").asText() + " at " + at)
                        .isLessThanOrEqualTo(pool.path("baseCapacity").asInt());
            }
        }
    }

    @Test
    void resettingReproducesExactlyTheSameRows() {
        seeder.seed(SEEDED_AT);
        Map<String, String> first = fingerprints();

        seeder.reset(SEEDED_AT);

        assertThat(fingerprints()).isEqualTo(first);
        assertThat(first.values()).doesNotContainNull();
    }

    @Test
    void seedingOverExistingDemoDataFailsAndChangesNothing() {
        seeder.seed(SEEDED_AT);
        Map<String, String> before = fingerprints();

        assertThatThrownBy(() -> seeder.seed(SEEDED_AT)).isInstanceOf(ApiException.class);

        assertThat(fingerprints()).isEqualTo(before);
    }

    @Test
    void theResetEndpointDiscardsChangesAndRestoresTheDemoFactory() {
        seeder.seed(SEEDED_AT);
        String wheels = idOf(get("/api/resources?kind=MACHINE").getBody()
                .path("items"), "Polishing wheels");
        put("/api/resources/" + wheels, """
                {"name": "Polishing wheels", "category": "POLISHING_WHEEL", "capacity": 99,
                 "hourlyCost": "140.00", "status": "ACTIVE", "version": 0}""");
        createFactory("Scratch Works");

        ResponseEntity<JsonNode> reset = post("/api/demo/reset", "{}");

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/factories").getBody().path("totalItems").asLong()).isEqualTo(1);
        String factoryId = reset.getBody().path("factoryId").asText();
        assertThat(get("/api/factories/" + factoryId).getBody().path("name").asText())
                .isEqualTo("Surat Diamond Works");
        JsonNode restored = get("/api/resources/" + wheels).getBody();
        assertThat(restored.path("capacity").asInt()).isEqualTo(6);
        assertThat(restored.path("version").asLong()).isZero();
    }

    @Test
    void systemInfoSaysResetIsAvailable() {
        assertThat(get("/api/system/info").getBody().path("demo").path("resetEnabled").asBoolean()).isTrue();
    }

    private static String idOf(JsonNode items, String name) {
        for (JsonNode item : items) {
            if (item.path("name").asText().equals(name)) {
                return item.path("id").asText();
            }
        }
        throw new AssertionError("No item named " + name);
    }

    /** A digest of every row of every table, so that any difference in any column shows. */
    private Map<String, String> fingerprints() {
        Map<String, String> fingerprints = new TreeMap<>();
        for (String table : TABLES) {
            fingerprints.put(table, jdbc.queryForObject(
                    "select md5(string_agg(t::text, '|' order by t::text)) from " + table + " t", String.class));
        }
        return fingerprints;
    }
}
