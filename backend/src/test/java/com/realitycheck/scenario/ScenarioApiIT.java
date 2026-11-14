package com.realitycheck.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Scenario and change management, branching, validation and isolation from live data (spec 06). */
class ScenarioApiIT extends ScenarioTestSupport {

    static final String[] LIVE_TABLES = {"factory", "working_shift", "calendar_break", "resource", "resource_availability",
            "workflow", "workflow_stage", "stage_requirement", "production_order", "production_batch",
            "operational_event"};

    @Test
    void createsGetsAndListsAScenario() {
        ResponseEntity<JsonNode> created = post("/api/scenarios", """
                {"factoryId": "%s", "name": "  Plan A  ", "description": "First idea", "forkAt": "%s"}"""
                .formatted(factoryId, fork.plusMillis(700)));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode scenario = created.getBody();
        assertThat(scenario.path("name").asText()).isEqualTo("Plan A");
        assertThat(scenario.path("forkAt").asText()).isEqualTo(fork.toString());
        assertThat(scenario.path("baseline").asBoolean()).isTrue();
        assertThat(scenario.path("includeFutureOrders").asBoolean()).isTrue();
        assertThat(scenario.path("changes")).isEmpty();
        assertThat(scenario.path("latestRun").isNull()).isTrue();
        assertThat(created.getHeaders().getLocation().getPath()).isEqualTo("/api/scenarios/" + scenario.path("id").asText());

        JsonNode list = get("/api/scenarios?factoryId=" + factoryId).getBody();
        assertThat(list.path("totalItems").asLong()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("changeCount").asInt()).isZero();
        assertThat(get("/api/scenarios").getBody().path("totalItems").asLong()).isEqualTo(1);
    }

    @Test
    void rejectsAForkOutsideRecordedHistoryABadNameAndADuplicate() {
        assertThat(errorFields(post("/api/scenarios", """
                {"factoryId": "%s", "name": "Future", "forkAt": "%s"}"""
                .formatted(factoryId, Instant.now().plus(1, ChronoUnit.HOURS))))).containsExactly("forkAt");
        assertThat(errorFields(post("/api/scenarios", """
                {"factoryId": "%s", "name": "Too early", "forkAt": "%s"}"""
                .formatted(factoryId, placedAt.minusSeconds(60))))).containsExactly("forkAt");
        assertThat(errorFields(post("/api/scenarios", """
                {"factoryId": "%s", "name": " ", "forkAt": "%s"}""".formatted(factoryId, fork))))
                .containsExactly("name");
        assertThat(errorFields(post("/api/scenarios", """
                {"factoryId": "%s", "name": "%s", "forkAt": "%s"}""".formatted(factoryId, "x".repeat(121), fork))))
                .containsExactly("name");
        assertThat(errorFields(post("/api/scenarios", """
                {"factoryId": "%s", "name": "No fork"}""".formatted(factoryId)))).containsExactly("forkAt");
        createScenario("Plan A");
        assertThat(post("/api/scenarios", """
                {"factoryId": "%s", "name": "plan a", "forkAt": "%s"}""".formatted(factoryId, fork))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void patchesWithTheCurrentVersionAndRejectsAStaleOne() {
        String id = createScenario("Plan A");

        ResponseEntity<JsonNode> renamed = patch("/api/scenarios/" + id,
                "{\"name\": \"Plan B\", \"description\": \"Better\", \"version\": 0}");
        ResponseEntity<JsonNode> stale = patch("/api/scenarios/" + id, "{\"name\": \"Plan C\", \"version\": 0}");

        assertThat(renamed.getStatusCode()).as(String.valueOf(renamed.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().path("name").asText()).isEqualTo("Plan B");
        assertThat(renamed.getBody().path("description").asText()).isEqualTo("Better");
        assertThat(renamed.getBody().path("version").asLong()).isEqualTo(1);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().path("code").asText()).isEqualTo("STALE_VERSION");
        ResponseEntity<JsonNode> cleared = patch("/api/scenarios/" + id, "{\"description\": \"\", \"version\": 1}");
        assertThat(cleared.getBody().path("description").isNull()).isTrue();
        assertThat(cleared.getBody().path("name").asText()).isEqualTo("Plan B");
    }

    @Test
    void addsEditsAndDeletesChangesRenumberingWithoutGaps() {
        String id = createScenario("Plan A");

        ResponseEntity<JsonNode> first = addChange(id, "RESOURCE_CAPACITY",
                "{\"resourceId\": \"%s\", \"mode\": \"DELTA\", \"value\": 2}".formatted(wheels));
        ResponseEntity<JsonNode> second = addChange(id, "DEMAND_VOLUME", "{\"percent\": 20}");
        ResponseEntity<JsonNode> third = addChange(id, "PROCESSING_RATE",
                "{\"stageId\": \"%s\", \"mode\": \"PERCENT\", \"value\": \"-12.5\"}".formatted(cutting));

        assertThat(first.getStatusCode()).as(String.valueOf(first.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().path("ordinal").asInt()).isEqualTo(1);
        assertThat(first.getBody().path("summary").asText()).isEqualTo("Wheels: capacity +2 units, 2 to 4 units");
        assertThat(second.getBody().path("ordinal").asInt()).isEqualTo(2);
        assertThat(third.getBody().path("summary").asText()).isEqualTo("Cutting: rate -12.5%, 12 to 10.5");

        String secondId = second.getBody().path("id").asText();
        ResponseEntity<JsonNode> edited = put("/api/scenarios/" + id + "/changes/" + secondId,
                "{\"payload\": {\"percent\": 35}}");
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().path("payload").path("percent").asInt()).isEqualTo(35);
        assertThat(put("/api/scenarios/" + id + "/changes/" + secondId,
                "{\"changeType\": \"RESOURCE_CAPACITY\", \"payload\": {\"percent\": 35}}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(delete("/api/scenarios/" + id + "/changes/" + first.getBody().path("id").asText())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode scenario = get("/api/scenarios/" + id).getBody();
        assertThat(texts(scenario.path("changes"), "changeType")).containsExactly("DEMAND_VOLUME", "PROCESSING_RATE");
        assertThat(texts(scenario.path("changes"), "ordinal")).containsExactly("1", "2");
        assertThat(scenario.path("baseline").asBoolean()).isFalse();
        // Each add, edit and delete moved the aggregate's version.
        assertThat(scenario.path("version").asLong()).isEqualTo(5);
    }

    @Test
    void rejectsInvalidPayloadsAndReferencesOutsideTheFactory() {
        String id = createScenario("Plan A");
        String otherFactory = createFactory("Other Works");
        String foreignPool = createPool(otherFactory, "MACHINE", "Foreign saws", 3);

        assertThat(errorFields(addChange(id, "RESOURCE_CAPACITY",
                "{\"resourceId\": \"%s\", \"mode\": \"DELTA\", \"value\": 0}".formatted(wheels))))
                .containsExactly("payload.value");
        assertThat(errorFields(addChange(id, "RESOURCE_CAPACITY",
                "{\"resourceId\": \"%s\", \"mode\": \"DELTA\", \"value\": 1}".formatted(foreignPool))))
                .containsExactly("payload.resourceId");
        assertThat(errorFields(addChange(id, "PROCESSING_RATE",
                "{\"stageId\": \"%s\", \"mode\": \"ABSOLUTE\", \"value\": 5}".formatted(wheels))))
                .containsExactly("payload.stageId");
        assertThat(errorFields(addChange(id, "DEMAND_VOLUME", "{\"percent\": 20, \"extra\": true}")))
                .containsExactly("payload.extra");
        assertThat(errorFields(addChange(id, "WORKING_SCHEDULE",
                "{\"mode\": \"ADD\", \"shifts\": [{\"dayOfWeek\": 1, \"startTime\": \"10:00\", \"endTime\": \"12:00\"}]}")))
                .containsExactly("payload.shifts");
        assertThat(addChange(id, "TELEPORT", "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/scenarios/" + id).getBody().path("changes")).isEmpty();
    }

    @Test
    void aChangeThatWouldBreakALaterOneIsRejected() {
        String id = createScenario("Plan A");
        String replace = addChange(id, "WORKING_SCHEDULE",
                "{\"mode\": \"REPLACE\", \"shifts\": [{\"dayOfWeek\": 1, \"startTime\": \"09:00\", \"endTime\": \"17:00\"}]}")
                .getBody().path("id").asText();
        addChange(id, "WORKING_SCHEDULE",
                "{\"mode\": \"ADD\", \"shifts\": [{\"dayOfWeek\": 1, \"startTime\": \"18:00\", \"endTime\": \"22:00\"}]}");

        // Widening the replacement to 20:00 would make the added evening shift overlap it.
        ResponseEntity<JsonNode> edited = put("/api/scenarios/" + id + "/changes/" + replace,
                "{\"payload\": {\"mode\": \"REPLACE\", \"shifts\": [{\"dayOfWeek\": 1, \"startTime\": \"09:00\", \"endTime\": \"20:00\"}]}}");

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorFields(edited)).containsExactly("changes[2].payload.shifts");
    }

    @Test
    void branchingCopiesTheParentsChangesOnceAndMarksThemInherited() {
        String parent = createScenario("Parent");
        addChange(parent, "RESOURCE_CAPACITY", "{\"resourceId\": \"%s\", \"mode\": \"DELTA\", \"value\": 2}".formatted(wheels));
        addChange(parent, "DEMAND_VOLUME", "{\"percent\": 10}");

        ResponseEntity<JsonNode> child = post("/api/scenarios", """
                {"factoryId": "%s", "name": "Child", "parentScenarioId": "%s"}""".formatted(factoryId, parent));
        String childId = child.getBody().path("id").asText();
        addChange(parent, "PROCESSING_RATE", "{\"stageId\": \"%s\", \"mode\": \"ABSOLUTE\", \"value\": 20}".formatted(cutting));
        addChange(childId, "RESOURCE_UNAVAILABLE", "{\"resourceId\": \"%s\", \"units\": 1}".formatted(polishers));

        assertThat(child.getStatusCode()).as(String.valueOf(child.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(child.getBody().path("parentScenarioId").asText()).isEqualTo(parent);
        assertThat(child.getBody().path("forkAt").asText()).isEqualTo(fork.toString());
        JsonNode reloaded = get("/api/scenarios/" + childId).getBody();
        assertThat(texts(reloaded.path("changes"), "changeType"))
                .containsExactly("RESOURCE_CAPACITY", "DEMAND_VOLUME", "RESOURCE_UNAVAILABLE");
        assertThat(texts(reloaded.path("changes"), "inherited")).containsExactly("true", "true", "false");
        assertThat(get("/api/scenarios/" + parent).getBody().path("changes")).hasSize(3);

        String otherFactory = createFactory("Other Works");
        assertThat(errorFields(post("/api/scenarios", """
                {"factoryId": "%s", "name": "Stray", "parentScenarioId": "%s"}""".formatted(otherFactory, parent))))
                .containsExactly("parentScenarioId");
    }

    @Test
    void validationPreviewsTheChangedConfigurationAndStoresNothing() {
        String id = createScenario("Plan A");
        addChange(id, "RESOURCE_CAPACITY", "{\"resourceId\": \"%s\", \"mode\": \"DELTA\", \"value\": 2}".formatted(wheels));
        addChange(id, "RESOURCE_CAPACITY", "{\"resourceId\": \"%s\", \"mode\": \"PERCENT\", \"value\": 10}".formatted(saws));
        addChange(id, "DEMAND_VOLUME", "{\"percent\": 20}");
        addChange(id, "WORKING_SCHEDULE",
                "{\"mode\": \"REPLACE\", \"shifts\": [{\"dayOfWeek\": 1, \"startTime\": \"09:00\", \"endTime\": \"17:00\"}]}");
        String before = fingerprint(LIVE_TABLES) + fingerprint("scenario", "scenario_change", "simulation_run");

        JsonNode validation = post("/api/scenarios/" + id + "/validate", "").getBody();

        assertThat(validation.path("valid").asBoolean()).as(validation.toString()).isTrue();
        assertThat(validation.path("errors")).isEmpty();
        // Saws +10% of 1 rounds down to 1: no effect.
        assertThat(validation.path("warnings")).hasSize(1);
        assertThat(validation.path("warnings").get(0).path("code").asText()).isEqualTo("NO_EFFECT");
        assertThat(validation.path("warnings").get(0).path("ordinal").asInt()).isEqualTo(2);
        JsonNode preview = validation.path("preview");
        JsonNode wheelPreview = byField(preview.path("pools"), "resourceId", wheels);
        assertThat(wheelPreview.path("capacityBefore").asInt()).isEqualTo(2);
        assertThat(wheelPreview.path("capacityAfter").asInt()).isEqualTo(4);
        JsonNode polishingPreview = byField(preview.path("stages"), "stageId", polishing);
        assertThat(polishingPreview.path("maxSlotsBefore").asInt()).isEqualTo(2);
        assertThat(polishingPreview.path("maxSlotsAfter").asInt()).isEqualTo(2); // four polishers, two per wheel
        assertThat(preview.path("weeklyWorkingSecondsBefore").asLong()).isEqualTo(7 * (18 * 3600 - 60));
        assertThat(preview.path("weeklyWorkingSecondsAfter").asLong()).isEqualTo(8 * 3600);
        // Three batches, all still queued at cutting: 90 stones, +20%.
        assertThat(preview.path("unstartedStonesBefore").asText()).isEqualTo("90.0000");
        assertThat(preview.path("unstartedStonesAfter").asText()).isEqualTo("108.0000");
        assertThat(validation.path("baseStateHash").asText()).matches("[0-9a-f]{64}");
        assertThat(validation.path("inputHash").asText()).matches("[0-9a-f]{64}")
                .isNotEqualTo(validation.path("baseStateHash").asText());
        assertThat(fingerprint(LIVE_TABLES) + fingerprint("scenario", "scenario_change", "simulation_run"))
                .isEqualTo(before);
    }

    @Test
    void scenariosWithTheSameForkShareTheirStartingStateHash() {
        String a = createScenario("Plan A");
        String b = createScenario("Plan B");
        addChange(b, "DEMAND_VOLUME", "{\"percent\": 50}");

        JsonNode first = post("/api/scenarios/" + a + "/validate", "").getBody();
        JsonNode second = post("/api/scenarios/" + b + "/validate", "").getBody();

        assertThat(second.path("baseStateHash").asText()).isEqualTo(first.path("baseStateHash").asText());
        assertThat(second.path("inputHash").asText()).isNotEqualTo(first.path("inputHash").asText());
        // A scenario with no changes runs exactly its base: the input differs only by the algorithm version.
        assertThat(first.path("inputHash").asText()).isNotEqualTo(first.path("baseStateHash").asText());
    }

    @Test
    void aChangeThatMakesAStageImpossibleIsReportedBeforeAnyRun() {
        String id = createScenario("No wheels");
        addChange(id, "RESOURCE_CAPACITY", "{\"resourceId\": \"%s\", \"mode\": \"ABSOLUTE\", \"value\": 0}".formatted(wheels));

        JsonNode validation = post("/api/scenarios/" + id + "/validate", "").getBody();

        assertThat(validation.path("valid").asBoolean()).isFalse();
        assertThat(texts(validation.path("errors"), "code")).contains("REQUIREMENT_EXCEEDS_CAPACITY");
    }

    @Test
    void unknownScenariosAndChangesAre404() {
        String id = createScenario("Plan A");

        assertThat(get("/api/scenarios/00000000-0000-0000-0000-000000000009").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete("/api/scenarios/" + id + "/changes/00000000-0000-0000-0000-000000000009").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/scenarios/00000000-0000-0000-0000-000000000009/validate", "").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static JsonNode byField(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (node.path(field).asText().equals(value)) {
                return node;
            }
        }
        throw new AssertionError("No element with " + field + "=" + value + " in " + array);
    }
}
