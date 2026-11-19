package com.realitycheck.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WorkflowApiIT extends IntegrationTest {

    private String factoryId;
    private String wheels;
    private String polishers;

    @BeforeEach
    void createFactoryAndPools() {
        factoryId = createFactory("Works");
        wheels = createPool(factoryId, "MACHINE", "Wheels", 6);
        polishers = createPool(factoryId, "EMPLOYEE", "Polishers", 13);
    }

    @Test
    void createsAWorkflowAndReadsItsStagesInSequenceOrder() {
        ResponseEntity<JsonNode> created = post("/api/workflows", twoStageWorkflow("Main", "ACTIVE"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().path("id").asText();
        JsonNode stages = get("/api/workflows/" + id + "/stages").getBody();

        assertThat(texts(stages.path("items"), "code")).containsExactly("CUTTING", "POLISHING");
        assertThat(stages.path("totalItems").asLong()).isEqualTo(2);

        JsonNode polishing = stages.path("items").get(1);
        assertThat(polishing.path("sequence").asInt()).isEqualTo(2);
        assertThat(polishing.path("stonesPerHour").isTextual()).isTrue();
        assertThat(polishing.path("stonesPerHour").asText()).isEqualTo("6.0000");
        assertThat(polishing.path("setupSeconds").asLong()).isEqualTo(900);
        assertThat(polishing.path("stoneYield").asText()).isEqualTo("0.990000");
        assertThat(polishing.path("caratYield").asText()).isEqualTo("0.820000");
        assertThat(polishing.path("fixedCostPerJob").asText()).isEqualTo("150.00");
    }

    @Test
    void reportsEachRequirementWithItsPoolAndTheSlotsTheStageCanRun() {
        String id = post("/api/workflows", twoStageWorkflow("Main", "ACTIVE")).getBody().path("id").asText();

        JsonNode workflow = get("/api/workflows/" + id).getBody();
        JsonNode cutting = workflow.path("stages").get(0);
        JsonNode polishing = workflow.path("stages").get(1);

        assertThat(cutting.path("requirements")).isEmpty();
        assertThat(cutting.path("maxSlots").isNull()).isTrue();

        // Machines are listed before employees.
        assertThat(texts(polishing.path("requirements"), "resourceName")).containsExactly("Wheels", "Polishers");
        assertThat(polishing.path("requirements").get(1).path("unitsPerSlot").asInt()).isEqualTo(2);
        assertThat(polishing.path("requirements").get(1).path("poolCapacity").asInt()).isEqualTo(13);
        assertThat(polishing.path("requirements").get(1).path("kind").asText()).isEqualTo("EMPLOYEE");
        // 6 wheels allow 6 slots; 13 polishers at 2 per slot allow 6.
        assertThat(polishing.path("maxSlots").asInt()).isEqualTo(6);
    }

    @Test
    void slotCapacityFollowsThePoolWhenItChanges() {
        String id = post("/api/workflows", twoStageWorkflow("Main", "ACTIVE")).getBody().path("id").asText();

        put("/api/resources/" + polishers, """
                {"name": "Polishers", "category": "TEST_CATEGORY", "capacity": 7,
                 "hourlyCost": "100.00", "status": "ACTIVE", "version": 0}""");

        assertThat(get("/api/workflows/" + id).getBody().path("stages").get(1).path("maxSlots").asInt())
                .isEqualTo(3);
    }

    @Test
    void aPoolShowsTheStagesOfTheActiveWorkflowThatRequireIt() {
        post("/api/workflows", twoStageWorkflow("Main", "ACTIVE"));
        post("/api/workflows", twoStageWorkflow("Draft idea", "DRAFT"));

        JsonNode requiredBy = get("/api/resources/" + polishers).getBody().path("requiredByStages");
        JsonNode listed = get("/api/resources?factoryId=" + factoryId + "&kind=MACHINE").getBody();

        assertThat(requiredBy).hasSize(1);
        assertThat(requiredBy.get(0).path("stageCode").asText()).isEqualTo("POLISHING");
        assertThat(requiredBy.get(0).path("unitsPerSlot").asInt()).isEqualTo(2);
        assertThat(listed.path("items").get(0).path("requiredByStages").get(0).path("stageName").asText())
                .isEqualTo("Polishing");
    }

    @Test
    void aPoolRequiredByTheActiveWorkflowCannotBeDeactivated() {
        post("/api/workflows", twoStageWorkflow("Main", "ACTIVE"));

        ResponseEntity<JsonNode> response = put("/api/resources/" + wheels, """
                {"name": "Wheels", "category": "TEST_CATEGORY", "capacity": 6,
                 "hourlyCost": "100.00", "status": "INACTIVE", "version": 0}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("detail").asText()).contains("Polishing");
        assertThat(get("/api/resources/" + wheels).getBody().path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void aFactoryHasAtMostOneActiveWorkflow() {
        post("/api/workflows", twoStageWorkflow("Main", "ACTIVE"));

        ResponseEntity<JsonNode> second = post("/api/workflows", twoStageWorkflow("Second", "ACTIVE"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().path("detail").asText()).contains("already has an active workflow");
        assertThat(post("/api/workflows", twoStageWorkflow("Second", "DRAFT")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(get("/api/factories/" + factoryId).getBody().path("counts").path("workflows").asLong())
                .isEqualTo(2);
    }

    @Test
    void listsWorkflowsWithTheActiveOneFirst() {
        post("/api/workflows", twoStageWorkflow("Alpha draft", "DRAFT"));
        post("/api/workflows", twoStageWorkflow("Zulu live", "ACTIVE"));

        JsonNode list = get("/api/workflows?factoryId=" + factoryId).getBody();

        assertThat(texts(list.path("items"), "name")).containsExactly("Zulu live", "Alpha draft");
        assertThat(list.path("items").get(0).path("stageCount").asInt()).isEqualTo(2);
    }

    @Test
    void rejectsAWorkflowWithStructuralProblemsAndStoresNothing() {
        String otherPool = createPool(createFactory("Other Works"), "MACHINE", "Foreign", 1);

        ResponseEntity<JsonNode> response = post("/api/workflows", """
                {"factoryId": "%s", "name": "Broken", "status": "DRAFT", "stages": [
                  %s,
                  %s,
                  %s]}""".formatted(factoryId,
                stage("CUTTING", 1, "[{\"resourceId\": \"%s\", \"unitsPerSlot\": 1}]".formatted(otherPool)),
                stage("CUTTING", 2, "[{\"resourceId\": \"00000000-0000-0000-0000-000000000009\", \"unitsPerSlot\": 1}]"),
                stage("POLISHING", 5, "[]")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorFields(response)).containsExactlyInAnyOrder(
                "stages[0].requirements[0].resourceId",
                "stages[1].code",
                "stages[1].requirements[0].resourceId",
                "stages[2].sequence");
        assertThat(get("/api/workflows?factoryId=" + factoryId).getBody().path("totalItems").asLong()).isZero();
    }

    @Test
    void rejectsStageParametersOutsideTheirRange() {
        ResponseEntity<JsonNode> response = post("/api/workflows", """
                {"factoryId": "%s", "name": "Bad numbers", "status": "DRAFT", "stages": [
                  {"code": "cutting", "name": "Cutting", "sequence": 1, "stonesPerHour": "0",
                   "setupSeconds": -1, "stoneYield": "1.1", "caratYield": "0", "fixedCostPerJob": "-1"}]}"""
                .formatted(factoryId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorFields(response)).containsExactlyInAnyOrder(
                "stages[0].code", "stages[0].stonesPerHour", "stages[0].setupSeconds",
                "stages[0].stoneYield", "stages[0].caratYield", "stages[0].fixedCostPerJob");
    }

    @Test
    void theDatabaseItselfEnforcesStageRangesAndTheSingleActiveWorkflow() {
        String id = post("/api/workflows", twoStageWorkflow("Main", "ACTIVE")).getBody().path("id").asText();
        post("/api/workflows", twoStageWorkflow("Draft", "DRAFT"));

        assertThatThrownBy(() -> jdbc.update(
                "update workflow_stage set stones_per_hour = 0 where workflow_id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update workflow_stage set stone_yield = 1.1 where workflow_id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update workflow_stage set sequence = 1 where workflow_id = ?::uuid and code = 'POLISHING'", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update workflow set status = 'ACTIVE' where name = 'Draft'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anImportSetsStageKindsDelaysAndRoutes() {
        String id = post("/api/workflows", """
                {"factoryId": "%s", "name": "Imported", "status": "ACTIVE", "stages": [%s, %s, %s]}"""
                .formatted(factoryId, stage("INWARD", 1, "[]"), stage("PARKING", 2, "[]"), stage("CUTTING", 3, "[]")))
                .getBody().path("id").asText();

        ResponseEntity<JsonNode> configured = put("/api/workflows/" + id + "/configuration", """
                {"stages": [
                   {"code": "PARKING", "kind": "PARKING", "delayDeciles": [0, 60, 60, 120, 120, 180, 240, 300, 360, 600]},
                   {"code": "CUTTING", "kind": "WORKING", "handlingDeciles": [0, 0, 0, 0, 60, 60, 60, 120, 120, 240]}],
                 "routes": [{"code": "R1", "name": "Route one", "steps": [
                   {"stageCode": "INWARD", "visitPercent": 100}, {"stageCode": "PARKING", "visitPercent": 40},
                   {"stageCode": "CUTTING", "visitPercent": 100}]}]}""");

        assertThat(configured.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode stages = configured.getBody().path("stages");
        assertThat(stages.get(1).path("kind").asText()).isEqualTo("PARKING");
        assertThat(stages.get(1).path("delayDeciles").get(9).asLong()).isEqualTo(600);
        assertThat(stages.get(2).path("handlingDeciles").get(9).asLong()).isEqualTo(240);

        // A batch may now follow the route.
        ResponseEntity<JsonNode> order = post("/api/orders", """
                {"factoryId": "%s", "orderNumber": "L0001", "priority": 3, "placedAt": "2026-01-05T04:00:00Z",
                 "batches": [{"stoneCount": 10, "roughCarats": "12", "routeCode": "R1"}]}""".formatted(factoryId));
        assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void rejectsAConfigurationThatDoesNotFitTheWorkflow() {
        String id = post("/api/workflows", twoStageWorkflow("Main", "ACTIVE")).getBody().path("id").asText();

        assertThat(put("/api/workflows/" + id + "/configuration", """
                {"stages": [{"code": "NOPE", "kind": "PARKING", "delayDeciles": [0,0,0,0,0,0,0,0,0,0]}]}""")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("/api/workflows/" + id + "/configuration", """
                {"stages": [{"code": "CUTTING", "kind": "PARKING", "delayDeciles": [5, 1]}]}""")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("/api/workflows/" + id + "/configuration", """
                {"routes": [{"code": "BACK", "name": "Backwards", "steps": [
                  {"stageCode": "POLISHING", "visitPercent": 100}, {"stageCode": "CUTTING", "visitPercent": 100}]}]}""")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/workflows/" + id).getBody().path("stages").get(0).path("kind").asText())
                .isEqualTo("WORKING");
    }

    @Test
    void anUnknownWorkflowIsNotFound() {
        assertThat(get("/api/workflows/00000000-0000-0000-0000-000000000001").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/workflows/00000000-0000-0000-0000-000000000001/stages").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String twoStageWorkflow(String name, String status) {
        // Stages are sent out of order on purpose.
        return """
                {"factoryId": "%s", "name": "%s", "status": "%s", "stages": [
                  {"code": "POLISHING", "name": "Polishing", "sequence": 2, "stonesPerHour": "6",
                   "setupSeconds": 900, "stoneYield": "0.99", "caratYield": "0.82", "fixedCostPerJob": "150",
                   "requirements": [{"resourceId": "%s", "unitsPerSlot": 1}, {"resourceId": "%s", "unitsPerSlot": 2}]},
                  %s]}""".formatted(factoryId, name, status, wheels, polishers, stage("CUTTING", 1, "[]"));
    }

    private static String stage(String code, int sequence, String requirementsJson) {
        return """
                {"code": "%s", "name": "%s", "sequence": %d, "stonesPerHour": "12", "setupSeconds": 1200,
                 "stoneYield": "0.985", "caratYield": "0.62", "fixedCostPerJob": "200", "requirements": %s}"""
                .formatted(code, code.charAt(0) + code.substring(1).toLowerCase(), sequence, requirementsJson);
    }
}
