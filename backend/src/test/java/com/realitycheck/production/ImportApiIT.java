package com.realitycheck.production;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Imports into a factory whose workflow is Cutting then Polishing. */
class ImportApiIT extends IntegrationTest {

    private final Instant base = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void createFactoryAndWorkflow() {
        String factoryId = createFactory("Works");
        String saws = createPool(factoryId, "MACHINE", "Saws", 1);
        ResponseEntity<JsonNode> workflow = post("/api/workflows", """
                {"factoryId": "%s", "name": "Main", "status": "ACTIVE", "stages": [
                  {"code": "CUTTING", "name": "Cutting", "sequence": 1, "stonesPerHour": "12", "setupSeconds": 0,
                   "stoneYield": "1", "caratYield": "0.6", "fixedCostPerJob": "0",
                   "requirements": [{"resourceId": "%s", "unitsPerSlot": 1}]},
                  {"code": "POLISHING", "name": "Polishing", "sequence": 2, "stonesPerHour": "6", "setupSeconds": 0,
                   "stoneYield": "1", "caratYield": "0.8", "fixedCostPerJob": "0",
                   "requirements": [{"resourceId": "%s", "unitsPerSlot": 1}]}]}"""
                .formatted(factoryId, saws, saws));
        assertThat(workflow.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String at(int hours) {
        return "\"" + base.plus(hours, ChronoUnit.HOURS) + "\"";
    }

    private String orders() {
        return """
                [{"row": 2, "orderNumber": "A-1", "customerRef": "Buyer", "priority": 4, "placedAt": %s, "dueAt": %s,
                  "dispatchedAt": %s, "packet": "P1", "stones": 10, "carats": "12.5"},
                 {"row": 3, "orderNumber": "a-1", "packet": "P2", "stones": 5, "carats": "6"},
                 {"row": 4, "orderNumber": "B-1", "placedAt": %s, "stones": 8, "carats": "9"}]"""
                .formatted(at(0), at(200), at(60), at(1));
    }

    private String movements() {
        return """
                [{"row": 2, "orderNumber": "A-1", "packet": "P1", "stage": "polishing", "startedAt": %s,
                  "completedAt": %s, "stonesOut": "10", "caratsOut": "6"},
                 {"row": 3, "orderNumber": "A-1", "packet": "P1", "stage": "Cutting", "startedAt": %s, "completedAt": %s,
                  "caratsOut": "8"},
                 {"row": 4, "orderNumber": "A-1", "packet": "P2", "stage": "CUTTING", "startedAt": %s, "completedAt": %s},
                 {"row": 5, "orderNumber": "A-1", "packet": "P2", "stage": "POLISHING", "startedAt": %s,
                  "completedAt": %s},
                 {"row": 6, "orderNumber": "B-1", "stage": "CUTTING", "startedAt": %s}]"""
                .formatted(at(20), at(30), at(2), at(10), at(3), at(12), at(15), at(40), at(5));
    }

    private ResponseEntity<JsonNode> importRows(boolean dryRun, String orders, String movements) {
        return post("/api/imports", """
                {"dryRun": %s, "orders": %s, "movements": %s}""".formatted(dryRun, orders, movements));
    }

    @Test
    void aDryRunCountsEverythingAndSavesNothing() {
        ResponseEntity<JsonNode> response = importRows(true, orders(), movements());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = response.getBody();
        assertThat(result.path("error").isNull()).isTrue();
        assertThat(result.path("saved").asBoolean()).isFalse();
        assertThat(result.path("orders").asInt()).isEqualTo(2);
        assertThat(result.path("packets").asInt()).isEqualTo(3);
        assertThat(result.path("movements").asInt()).isEqualTo(5);
        assertThat(result.path("dispatched").asInt()).isEqualTo(1);
        assertThat(result.path("firstEventAt").asText()).isEqualTo(base.toString());
        assertThat(get("/api/orders").getBody().path("totalItems").asLong()).isZero();
        assertThat(get("/api/events").getBody().path("totalItems").asLong()).isZero();
    }

    @Test
    void anImportReplaysEachPacketInTimeOrderAndDispatchesFinishedOrders() {
        JsonNode result = importRows(false, orders(), movements()).getBody();
        assertThat(result.path("saved").asBoolean()).isTrue();

        JsonNode list = get("/api/orders").getBody().path("items");
        assertThat(texts(list, "orderNumber")).containsExactly("A-1", "B-1");
        JsonNode a = list.get(0);
        assertThat(a.path("status").asText()).isEqualTo("DISPATCHED");
        assertThat(a.path("priority").asInt()).isEqualTo(4);
        assertThat(a.path("completedAt").asText()).isEqualTo(base.plus(40, ChronoUnit.HOURS).toString());

        JsonNode detail = get("/api/orders/" + a.path("id").asText()).getBody();
        JsonNode first = detail.path("batches").get(0);
        assertThat(first.path("currentStones").asText()).as("blank stones_out means no loss").isEqualTo("10.0000");
        assertThat(first.path("currentCarats").asText()).isEqualTo("6.0000");
        JsonNode second = detail.path("batches").get(1);
        assertThat(second.path("currentCarats").asText()).as("blank output keeps what entered").isEqualTo("6.0000");

        JsonNode b = get("/api/orders/" + list.get(1).path("id").asText()).getBody();
        assertThat(b.path("order").path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(b.path("batches").get(0).path("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void theFirstBrokenRuleNamesItsFileAndRowAndNothingIsKept() {
        String movements = movements().replace("\"caratsOut\": \"6\"", "\"caratsOut\": \"60\"");
        JsonNode result = importRows(false, orders(), movements).getBody();

        assertThat(result.path("saved").asBoolean()).isFalse();
        assertThat(result.path("error").path("file").asText()).isEqualTo("movements");
        assertThat(result.path("error").path("row").asInt()).isEqualTo(2);
        assertThat(result.path("error").path("message").asText()).contains("cannot exceed");
        assertThat(get("/api/orders").getBody().path("totalItems").asLong()).isZero();
        assertThat(get("/api/events").getBody().path("totalItems").asLong()).isZero();
    }

    @Test
    void reportsRowsThatDoNotMatchTheWorkflowOrTheOrdersFile() {
        assertThat(importRows(true, orders(), movements().replace("\"stage\": \"Cutting\"", "\"stage\": \"Sawing\""))
                .getBody().path("error").path("message").asText())
                .contains("Sawing is not in the active workflow", "CUTTING, POLISHING");
        assertThat(importRows(true, orders(), movements().replace("\"orderNumber\": \"B-1\"", "\"orderNumber\": \"Z\""))
                .getBody().path("error").path("message").asText()).isEqualTo("order Z is not in the orders file");
        JsonNode skipped = importRows(true, orders(), """
                [{"row": 7, "orderNumber": "B-1", "stage": "POLISHING", "startedAt": %s}]""".formatted(at(5)))
                .getBody().path("error");
        assertThat(skipped.path("row").asInt()).isEqualTo(7);
        assertThat(skipped.path("message").asText()).contains("not at that stage");
        JsonNode badOrder = importRows(true, orders().replace("\"priority\": 4", "\"priority\": 9"), "[]")
                .getBody().path("error");
        assertThat(badOrder.path("file").asText()).isEqualTo("orders");
        assertThat(badOrder.path("message").asText()).startsWith("priority");
        JsonNode disagree = importRows(true, orders().replace("\"orderNumber\": \"a-1\",",
                "\"orderNumber\": \"a-1\", \"placedAt\": " + at(1) + ","), "[]").getBody().path("error");
        assertThat(disagree.path("row").asInt()).isEqualTo(3);
        assertThat(disagree.path("message").asText()).contains("placed_at differs from row 2");
    }

    @Test
    void anEmptyOrdersListIsABadRequest() {
        assertThat(importRows(true, "[]", "[]").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(importRows(true, orders().replace("\"placedAt\": " + at(0) + ", ", ""), "[]").getBody()
                .path("error").path("message").asText()).isEqualTo("placed_at is required on the first row of an order");
    }
}
