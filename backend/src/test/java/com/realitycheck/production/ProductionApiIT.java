package com.realitycheck.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** A factory with a two-stage workflow: Cutting (one saw) then Polishing (one wheel and two polishers). */
class ProductionApiIT extends IntegrationTest {

    /** Events in these tests happen in the last three hours, so none is in the future. */
    private final Instant base = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private String factoryId;
    private String wheels;
    private String cutting;
    private String polishing;

    @BeforeEach
    void createFactoryAndWorkflow() {
        factoryId = createFactory("Works");
        String saws = createPool(factoryId, "MACHINE", "Saws", 1);
        wheels = createPool(factoryId, "MACHINE", "Wheels", 2);
        String polishers = createPool(factoryId, "EMPLOYEE", "Polishers", 4);
        JsonNode workflow = post("/api/workflows", """
                {"factoryId": "%s", "name": "Main", "status": "ACTIVE", "stages": [
                  {"code": "CUTTING", "name": "Cutting", "sequence": 1, "stonesPerHour": "12", "setupSeconds": 600,
                   "stoneYield": "0.98", "caratYield": "0.62", "fixedCostPerJob": "200",
                   "requirements": [{"resourceId": "%s", "unitsPerSlot": 1}]},
                  {"code": "POLISHING", "name": "Polishing", "sequence": 2, "stonesPerHour": "6", "setupSeconds": 900,
                   "stoneYield": "0.99", "caratYield": "0.82", "fixedCostPerJob": "150",
                   "requirements": [{"resourceId": "%s", "unitsPerSlot": 1}, {"resourceId": "%s", "unitsPerSlot": 2}]}]}"""
                .formatted(factoryId, saws, wheels, polishers)).getBody();
        cutting = workflow.path("stages").get(0).path("id").asText();
        polishing = workflow.path("stages").get(1).path("id").asText();
    }

    // ---- Orders ---------------------------------------------------------------------------

    @Test
    void placingAnOrderQueuesItsBatchesAtTheFirstStageAndRecordsIt() {
        ResponseEntity<JsonNode> created = post("/api/orders", order("ORD-1", 3, at(0), at(0).plus(5, ChronoUnit.DAYS),
                "{\"stoneCount\": 100, \"roughCarats\": \"130.5\"}, {\"stoneCount\": 60, \"roughCarats\": \"70\"}"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode order = created.getBody();
        assertThat(order.path("status").asText()).isEqualTo("PENDING");
        assertThat(order.path("batchCount").asInt()).isEqualTo(2);
        assertThat(order.path("totalStones").asInt()).isEqualTo(160);
        assertThat(order.path("totalRoughCarats").asText()).isEqualTo("200.5000");
        assertThat(order.path("delayed").asBoolean()).isFalse();
        assertThat(order.path("source").asText()).isEqualTo("ACTUAL");

        JsonNode detail = get(created.getHeaders().getLocation().getPath()).getBody();
        assertThat(texts(detail.path("batches"), "status")).containsExactly("QUEUED", "QUEUED");
        assertThat(texts(detail.path("batches"), "currentStageCode")).containsExactly("CUTTING", "CUTTING");
        assertThat(detail.path("batches").get(0).path("currentStones").asText()).isEqualTo("100.0000");
        assertThat(texts(detail.path("events"), "eventType"))
                .containsExactly("ORDER_CREATED", "STAGE_QUEUED", "STAGE_QUEUED");
        assertThat(detail.path("events").get(0).path("stoneQuantity").asText()).isEqualTo("160.0000");
        assertThat(detail.path("events").get(0).path("occurredAt").asText()).isEqualTo(at(0).toString());
        assertThat(get("/api/factories/" + factoryId).getBody().path("counts").path("orders").asLong()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidOrdersAndStoresNothing() {
        assertThat(errorFields(post("/api/orders", order("bad number!", 9, at(0), null,
                "{\"stoneCount\": 0, \"roughCarats\": \"0\"}"))))
                .containsExactlyInAnyOrder("orderNumber", "priority", "batches[0].stoneCount", "batches[0].roughCarats");
        assertThat(errorFields(post("/api/orders", order("ORD-1", 3, at(0), at(0).minusSeconds(60),
                "{\"stoneCount\": 10, \"roughCarats\": \"12\"}")))).containsExactly("dueAt");
        assertThat(errorFields(post("/api/orders", order("ORD-1", 3, Instant.now().plus(1, ChronoUnit.DAYS), null,
                "{\"stoneCount\": 10, \"roughCarats\": \"12\"}")))).containsExactly("placedAt");

        assertThat(get("/api/orders").getBody().path("totalItems").asLong()).isZero();
        assertThat(get("/api/events").getBody().path("totalItems").asLong()).isZero();
    }

    @Test
    void rejectsADuplicateOrderNumberAndAFactoryWithoutAWorkflow() {
        placeOrder("ORD-1", 100);
        assertThat(post("/api/orders", order("ord-1", 3, at(0), null, "{\"stoneCount\": 10, \"roughCarats\": \"12\"}"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String bare = createFactory("Bare Works");
        ResponseEntity<JsonNode> response = post("/api/orders", order("ORD-2", 3, at(0), null,
                "{\"stoneCount\": 10, \"roughCarats\": \"12\"}").replace(factoryId, bare));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("detail").asText()).contains("no active workflow");
    }

    @Test
    void listsOrdersEarliestDueFirstAndFiltersByStatusAndLateness() {
        post("/api/orders", order("LATE", 3, at(0).minus(10, ChronoUnit.DAYS), at(0).minus(2, ChronoUnit.DAYS),
                "{\"stoneCount\": 10, \"roughCarats\": \"12\"}"));
        post("/api/orders", order("SOON", 3, at(0), at(0).plus(2, ChronoUnit.DAYS),
                "{\"stoneCount\": 10, \"roughCarats\": \"12\"}"));
        post("/api/orders", order("OPEN-ENDED", 3, at(0), null, "{\"stoneCount\": 10, \"roughCarats\": \"12\"}"));
        String started = batchOf(placeOrder("STARTED", 10));
        append(started, "STAGE_STARTED", at(10), "");

        assertThat(texts(get("/api/orders").getBody().path("items"), "orderNumber"))
                .as("orders without a due date come last, by number")
                .containsExactly("LATE", "SOON", "OPEN-ENDED", "STARTED");
        assertThat(texts(get("/api/orders?delayed=true").getBody().path("items"), "orderNumber"))
                .containsExactly("LATE");
        assertThat(get("/api/orders?delayed=false").getBody().path("totalItems").asLong()).isEqualTo(3);
        assertThat(texts(get("/api/orders?status=IN_PROGRESS").getBody().path("items"), "orderNumber"))
                .containsExactly("STARTED");
        assertThat(get("/api/orders?status=PENDING").getBody().path("totalItems").asLong()).isEqualTo(3);
    }

    // ---- The batch state machine -------------------------------------------------------------

    @Test
    void aBatchMovesThroughEveryStageAndTheOrderCompletesAndDispatches() {
        String orderId = placeOrder("ORD-1", 100);
        String batch = batchOf(orderId);

        assertThat(append(batch, "STAGE_STARTED", at(10), "").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");

        ResponseEntity<JsonNode> cut = append(batch, "STAGE_COMPLETED", at(60),
                ", \"stoneQuantity\": \"98\", \"caratQuantity\": \"80.6\", \"durationSeconds\": 3000");
        assertThat(cut.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cut.getBody().path("stageCode").asText()).isEqualTo("CUTTING");
        JsonNode afterCutting = get("/api/orders/" + orderId).getBody().path("batches").get(0);
        assertThat(afterCutting.path("status").asText()).isEqualTo("QUEUED");
        assertThat(afterCutting.path("currentStageCode").asText()).isEqualTo("POLISHING");
        assertThat(afterCutting.path("currentStones").asText()).isEqualTo("98.0000");
        assertThat(afterCutting.path("currentCarats").asText()).isEqualTo("80.6000");
        assertThat(afterCutting.path("stoneCount").asInt()).isEqualTo(100);

        append(batch, "STAGE_STARTED", at(70), "");
        append(batch, "STAGE_COMPLETED", at(120), ", \"stoneQuantity\": \"97\", \"caratQuantity\": \"66.1\"");

        JsonNode detail = get("/api/orders/" + orderId).getBody();
        assertThat(detail.path("order").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("order").path("completedAt").asText()).isEqualTo(at(120).toString());
        assertThat(detail.path("order").path("batchesCompleted").asInt()).isEqualTo(1);
        assertThat(detail.path("batches").get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("batches").get(0).path("currentStageId").isNull()).isTrue();
        assertThat(texts(detail.path("events"), "eventType")).containsExactly(
                "ORDER_CREATED", "STAGE_QUEUED", "STAGE_STARTED", "STAGE_COMPLETED", "STAGE_QUEUED",
                "STAGE_STARTED", "STAGE_COMPLETED", "ORDER_COMPLETED");

        ResponseEntity<JsonNode> dispatched = post("/api/events", """
                {"eventType": "ORDER_DISPATCHED", "orderId": "%s", "occurredAt": "%s"}""".formatted(orderId, at(130)));
        assertThat(dispatched.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(orderStatus(orderId)).isEqualTo("DISPATCHED");
        assertThat(post("/api/events", """
                {"eventType": "ORDER_DISPATCHED", "orderId": "%s", "occurredAt": "%s"}""".formatted(orderId, at(140)))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void anOrderCompletesOnlyWhenEveryBatchHasFinished() {
        String orderId = post("/api/orders", order("ORD-1", 3, at(0), null,
                "{\"stoneCount\": 50, \"roughCarats\": \"60\"}, {\"stoneCount\": 40, \"roughCarats\": \"50\"}"))
                .getBody().path("id").asText();
        JsonNode batches = get("/api/orders/" + orderId).getBody().path("batches");
        String first = batches.get(0).path("id").asText();
        String second = batches.get(1).path("id").asText();

        finish(first, 10);
        assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");
        assertThat(get("/api/orders/" + orderId).getBody().path("order").path("batchesCompleted").asInt())
                .isEqualTo(1);

        finish(second, 60);
        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
    }

    @Test
    void rejectsStepsThatCannotHappen() {
        String orderId = placeOrder("ORD-1", 100);
        String batch = batchOf(orderId);

        assertUnprocessable(append(batch, "STAGE_COMPLETED", at(10),
                ", \"stoneQuantity\": \"98\", \"caratQuantity\": \"80\""), "not in progress");
        assertUnprocessable(post("/api/events", """
                {"eventType": "ORDER_DISPATCHED", "orderId": "%s"}""".formatted(orderId)), "cannot be dispatched until");

        append(batch, "STAGE_STARTED", at(10), "");
        assertUnprocessable(append(batch, "STAGE_STARTED", at(11), ""), "already in progress");
        assertUnprocessable(append(batch, "STAGE_COMPLETED", at(5),
                ", \"stoneQuantity\": \"98\", \"caratQuantity\": \"80\""), "before the batch's previous event");
        assertUnprocessable(append(batch, "STAGE_COMPLETED", at(20),
                ", \"stoneQuantity\": \"101\", \"caratQuantity\": \"80\""), "cannot exceed");
        assertUnprocessable(append(batch, "STAGE_COMPLETED", at(20),
                ", \"stoneQuantity\": \"98\", \"caratQuantity\": \"80\", \"stageId\": \"" + polishing + "\""),
                "not at that stage");

        assertThat(get("/api/events?batchId=" + batch).getBody().path("totalItems").asLong()).isEqualTo(2);
    }

    @Test
    void rejectsMalformedEvents() {
        String batch = batchOf(placeOrder("ORD-1", 100));

        assertThat(errorFields(post("/api/events", "{\"eventType\": \"STAGE_STARTED\"}"))).containsExactly("batchId");
        assertThat(errorFields(append(batch, "STAGE_COMPLETED", at(10), ""))).containsExactly("stoneQuantity");
        assertThat(errorFields(append(batch, "STAGE_QUEUED", at(10), ""))).containsExactly("eventType");
        assertThat(errorFields(append(batch, "ORDER_COMPLETED", at(10), ""))).containsExactly("eventType");
        assertThat(errorFields(append(batch, "STAGE_STARTED", Instant.now().plus(1, ChronoUnit.HOURS), "")))
                .containsExactly("occurredAt");
        assertThat(errorFields(append("00000000-0000-0000-0000-000000000001", "STAGE_STARTED", at(10), "")))
                .containsExactly("batchId");
        assertThat(errorFields(append(batch, "STAGE_STARTED", at(10), ", \"stoneQuantity\": \"-1\"")))
                .containsExactly("stoneQuantity");
    }

    @Test
    void twoSimultaneousStartsOfOneBatchGiveExactlyOneSuccess() throws Exception {
        String batch = batchOf(placeOrder("ORD-1", 100));
        Callable<HttpStatus> start = () -> HttpStatus.valueOf(
                append(batch, "STAGE_STARTED", at(10), "").getStatusCode().value());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpStatus>> results = pool.invokeAll(List.of(start, start));

            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_ENTITY);
        } finally {
            pool.shutdown();
        }
        assertThat(get("/api/events?batchId=" + batch + "&type=STAGE_STARTED").getBody().path("totalItems").asLong())
                .isEqualTo(1);
    }

    // ---- History is append-only; corrections ----------------------------------------------------

    @Test
    void theDatabaseRefusesToChangeOrRemoveAnEvent() {
        placeOrder("ORD-1", 100);

        assertThatThrownBy(() -> jdbc.update("update operational_event set occurred_at = now()"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from operational_event"))
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("select count(*) from operational_event", Long.class)).isEqualTo(2);
    }

    @Test
    void aCorrectionChangesWhatIsKnownWithoutChangingWhatWasRecorded() {
        String orderId = placeOrder("ORD-1", 100);
        String batch = batchOf(orderId);
        append(batch, "STAGE_STARTED", at(10), "");
        String completion = append(batch, "STAGE_COMPLETED", at(60),
                ", \"stoneQuantity\": \"99\", \"caratQuantity\": \"80.6\"").getBody().path("id").asText();
        String rowBefore = rowText(completion);

        ResponseEntity<JsonNode> correction = post("/api/events", """
                {"eventType": "CORRECTION", "correctsEventId": "%s", "stoneQuantity": "98",
                 "payload": {"reason": "miscount"}}""".formatted(completion));

        assertThat(correction.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(correction.getBody().path("correctsEventId").asText()).isEqualTo(completion);
        assertThat(correction.getBody().path("payload").path("reason").asText()).isEqualTo("miscount");
        assertThat(rowText(completion)).isEqualTo(rowBefore);
        assertThat(get("/api/orders/" + orderId).getBody().path("batches").get(0).path("currentStones").asText())
                .isEqualTo("98.0000");

        JsonNode inTimeline = get("/api/timeline?type=STAGE_COMPLETED").getBody().path("items").get(0);
        assertThat(inTimeline.path("stoneQuantity").asText()).isEqualTo("98.0000");
        assertThat(inTimeline.path("caratQuantity").asText()).isEqualTo("80.6000");
        assertThat(inTimeline.path("corrected").asBoolean()).isTrue();
        assertThat(inTimeline.path("original").path("stoneQuantity").asText()).isEqualTo("99.0000");

        assertThat(get("/api/timeline?type=CORRECTION").getBody().path("totalItems").asLong()).isZero();
        assertThat(get("/api/events?type=CORRECTION").getBody().path("totalItems").asLong()).isEqualTo(1);
    }

    @Test
    void aCorrectionThatLaterWorkContradictsIsRefused() {
        String batch = batchOf(placeOrder("ORD-1", 100));
        append(batch, "STAGE_STARTED", at(10), "");
        String completion = append(batch, "STAGE_COMPLETED", at(60),
                ", \"stoneQuantity\": \"98\", \"caratQuantity\": \"80.6\"").getBody().path("id").asText();
        append(batch, "STAGE_STARTED", at(70), "");
        append(batch, "STAGE_COMPLETED", at(120), ", \"stoneQuantity\": \"97\", \"caratQuantity\": \"66\"");

        assertUnprocessable(post("/api/events", """
                {"eventType": "CORRECTION", "correctsEventId": "%s", "stoneQuantity": "90"}""".formatted(completion)),
                "cannot exceed");
        assertThat(get("/api/events?type=CORRECTION").getBody().path("totalItems").asLong()).isZero();
    }

    @Test
    void voidingAStartPutsTheBatchBackInTheQueueAndHidesTheEventFromTheTimeline() {
        String orderId = placeOrder("ORD-1", 100);
        String batch = batchOf(orderId);
        String start = append(batch, "STAGE_STARTED", at(10), "").getBody().path("id").asText();

        assertThat(post("/api/events", """
                {"eventType": "CORRECTION", "correctsEventId": "%s", "voids": true}""".formatted(start))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(get("/api/orders/" + orderId).getBody().path("batches").get(0).path("status").asText())
                .isEqualTo("QUEUED");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(get("/api/timeline?type=STAGE_STARTED").getBody().path("totalItems").asLong()).isZero();
        JsonNode withVoided = get("/api/timeline?type=STAGE_STARTED&includeVoided=true").getBody();
        assertThat(withVoided.path("items").get(0).path("voided").asBoolean()).isTrue();
        assertThat(append(batch, "STAGE_STARTED", at(20), "").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertUnprocessable(post("/api/events", """
                {"eventType": "CORRECTION", "correctsEventId": "%s", "voids": true}""".formatted(start)),
                "has been voided");
    }

    @Test
    void voidingACompletionReturnsTheBatchToInProgressAtThatStage() {
        String orderId = placeOrder("ORD-1", 100);
        String batch = batchOf(orderId);
        append(batch, "STAGE_STARTED", at(10), "");
        String completion = append(batch, "STAGE_COMPLETED", at(60),
                ", \"stoneQuantity\": \"98\", \"caratQuantity\": \"80.6\"").getBody().path("id").asText();

        post("/api/events", """
                {"eventType": "CORRECTION", "correctsEventId": "%s", "voids": true}""".formatted(completion));

        JsonNode state = get("/api/orders/" + orderId).getBody().path("batches").get(0);
        assertThat(state.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(state.path("currentStageCode").asText()).isEqualTo("CUTTING");
        assertThat(state.path("currentStones").asText()).isEqualTo("100.0000");
        assertThat(texts(get("/api/timeline?order=asc&batchId=" + batch).getBody().path("items"), "eventType"))
                .containsExactly("STAGE_QUEUED", "STAGE_STARTED");
    }

    @Test
    void rejectsCorrectionsThatAreNotAllowed() {
        String orderId = placeOrder("ORD-1", 100);
        String batch = batchOf(orderId);
        String start = append(batch, "STAGE_STARTED", at(10), "").getBody().path("id").asText();
        String created = get("/api/events?type=ORDER_CREATED").getBody().path("items").get(0).path("id").asText();
        String correction = "{\"eventType\": \"CORRECTION\", \"correctsEventId\": \"%s\"%s}";

        assertThat(errorFields(post("/api/events", correction.formatted(start, "")))).containsExactly("voids");
        assertThat(errorFields(post("/api/events",
                correction.formatted(start, ", \"voids\": true, \"stoneQuantity\": \"5\"")))).containsExactly("voids");
        assertThat(errorFields(post("/api/events",
                correction.formatted("00000000-0000-0000-0000-000000000001", ", \"voids\": true"))))
                .containsExactly("correctsEventId");
        assertUnprocessable(post("/api/events", correction.formatted(start, ", \"stoneQuantity\": \"5\"")),
                "Only the output of a completed stage");
        assertUnprocessable(post("/api/events", correction.formatted(created, ", \"voids\": true")),
                "Only a stage start or a stage completion");

        finish(batch, 20, false, "9");
        String lastCompletion = get("/api/events?type=STAGE_COMPLETED&order=desc").getBody().path("items").get(0)
                .path("id").asText();
        assertUnprocessable(post("/api/events", correction.formatted(lastCompletion, ", \"voids\": true")),
                "cannot be reopened");
    }

    // ---- Timeline -------------------------------------------------------------------------------

    @Test
    void theTimelineIsNewestFirstAndFiltersByStageOrderTypeAndTime() {
        String first = placeOrder("ORD-1", 100);
        String second = placeOrder("ORD-2", 50);
        append(batchOf(first), "STAGE_STARTED", at(10), "");
        append(batchOf(first), "STAGE_COMPLETED", at(60), ", \"stoneQuantity\": \"98\", \"caratQuantity\": \"80\"");
        append(batchOf(second), "STAGE_STARTED", at(70), "");

        JsonNode all = get("/api/timeline").getBody();
        assertThat(all.path("totalItems").asLong()).isEqualTo(8);
        assertThat(all.path("items").get(0).path("eventType").asText()).isEqualTo("STAGE_STARTED");
        assertThat(all.path("items").get(0).path("orderNumber").asText()).isEqualTo("ORD-2");
        assertThat(all.path("items").get(0).path("batchNumber").asInt()).isEqualTo(1);
        assertThat(all.path("items").get(0).path("stageName").asText()).isEqualTo("Cutting");

        assertThat(get("/api/timeline?orderId=" + second).getBody().path("totalItems").asLong()).isEqualTo(3);
        assertThat(texts(get("/api/timeline?stageId=" + polishing).getBody().path("items"), "eventType"))
                .containsExactly("STAGE_QUEUED");
        assertThat(get("/api/timeline?type=STAGE_STARTED").getBody().path("totalItems").asLong()).isEqualTo(2);
        assertThat(get("/api/timeline?from=" + at(60) + "&to=" + at(70)).getBody().path("totalItems").asLong())
                .isEqualTo(3);
        assertThat(texts(get("/api/timeline?order=asc&size=2").getBody().path("items"), "eventType"))
                .containsExactly("ORDER_CREATED", "STAGE_QUEUED");
        assertThat(get("/api/timeline?size=3&page=2").getBody().path("items")).hasSize(2);

        assertThat(errorFields(get("/api/timeline?order=sideways"))).containsExactly("order");
        assertThat(errorFields(get("/api/timeline?from=" + at(70) + "&to=" + at(60)))).containsExactly("to");
    }

    @Test
    void theSummaryCountsEventsPerFactoryLocalDay() {
        // 18:00 and 19:00 UTC are 23:30 on the 5th and 00:30 on the 6th in Asia/Kolkata.
        post("/api/orders", order("ORD-1", 3, Instant.parse("2026-01-05T18:00:00Z"), null,
                "{\"stoneCount\": 10, \"roughCarats\": \"12\"}"));
        post("/api/orders", order("ORD-2", 3, Instant.parse("2026-01-05T19:00:00Z"), null,
                "{\"stoneCount\": 10, \"roughCarats\": \"12\"}"));

        JsonNode summary = get("/api/timeline/summary").getBody();

        assertThat(summary.path("timeZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(summary.path("firstEventAt").asText()).isEqualTo("2026-01-05T18:00:00Z");
        assertThat(texts(summary.path("buckets"), "start"))
                .containsExactly("2026-01-04T18:30:00Z", "2026-01-05T18:30:00Z");
        assertThat(summary.path("buckets").get(0).path("total").asLong()).isEqualTo(2);
        assertThat(summary.path("buckets").get(0).path("byType").path("ORDER_CREATED").asLong()).isEqualTo(1);
        assertThat(get("/api/timeline/summary?bucket=HOUR").getBody().path("buckets")).hasSize(2);
        assertThat(errorFields(get("/api/timeline/summary?bucket=WEEK"))).containsExactly("bucket");
    }

    // ---- State at a point in time -----------------------------------------------------------------

    @Test
    void reconstructsTheFactoryAtAnyInstantWithoutWritingAnything() {
        String orderId = placeOrder("ORD-1", 100);
        String batch = batchOf(orderId);
        append(batch, "STAGE_STARTED", at(10), "");
        append(batch, "STAGE_COMPLETED", at(60), ", \"stoneQuantity\": \"98\", \"caratQuantity\": \"80.6\"");
        append(batch, "STAGE_STARTED", at(70), "");
        long rowsBefore = jdbc.queryForObject("select count(*) from operational_event", Long.class);

        JsonNode before = state(at(-5));
        assertThat(before.path("batches")).isEmpty();
        assertThat(before.path("orders").path("PENDING").asLong()).isZero();

        JsonNode queued = state(at(5));
        assertThat(queued.path("orders").path("PENDING").asLong()).isEqualTo(1);
        assertThat(queued.path("stages").get(0).path("queued").asInt()).isEqualTo(1);
        assertThat(queued.path("batches").get(0).path("waitingSince").asText()).isEqualTo(at(0).toString());
        assertThat(queued.path("batches").get(0).path("inputStones").asText()).isEqualTo("100.0000");

        JsonNode cuttingInProgress = state(at(30));
        assertThat(cuttingInProgress.path("orders").path("IN_PROGRESS").asLong()).isEqualTo(1);
        assertThat(cuttingInProgress.path("stages").get(0).path("inProgress").asInt()).isEqualTo(1);
        assertThat(cuttingInProgress.path("batches").get(0).path("startedAt").asText()).isEqualTo(at(10).toString());
        assertThat(unitsInUse(cuttingInProgress, "Saws")).isEqualTo(1);
        assertThat(unitsInUse(cuttingInProgress, "Polishers")).isZero();

        JsonNode waitingForPolishing = state(at(65));
        assertThat(waitingForPolishing.path("stages").get(0).path("completed").asInt()).isEqualTo(1);
        assertThat(waitingForPolishing.path("stages").get(1).path("queued").asInt()).isEqualTo(1);
        assertThat(waitingForPolishing.path("batches").get(0).path("stageCode").asText()).isEqualTo("POLISHING");
        assertThat(waitingForPolishing.path("batches").get(0).path("inputStones").asText()).isEqualTo("98.0000");
        assertThat(unitsInUse(waitingForPolishing, "Saws")).isZero();

        JsonNode polishingInProgress = state(at(80));
        assertThat(unitsInUse(polishingInProgress, "Wheels")).isEqualTo(1);
        assertThat(unitsInUse(polishingInProgress, "Polishers")).isEqualTo(2);
        assertThat(polishingInProgress.path("source").asText()).isEqualTo("ACTUAL");

        assertThat(jdbc.queryForObject("select count(*) from operational_event", Long.class)).isEqualTo(rowsBefore);
    }

    @Test
    void aLaterCorrectionChangesWhatIsKnownAboutAnEarlierInstant() {
        String batch = batchOf(placeOrder("ORD-1", 100));
        append(batch, "STAGE_STARTED", at(10), "");
        String completion = append(batch, "STAGE_COMPLETED", at(60),
                ", \"stoneQuantity\": \"99\", \"caratQuantity\": \"80.6\"").getBody().path("id").asText();
        assertThat(state(at(65)).path("batches").get(0).path("inputStones").asText()).isEqualTo("99.0000");

        post("/api/events", """
                {"eventType": "CORRECTION", "correctsEventId": "%s", "stoneQuantity": "98"}""".formatted(completion));

        assertThat(state(at(65)).path("batches").get(0).path("inputStones").asText()).isEqualTo("98.0000");
    }

    @Test
    void stateReflectsCapacityWindowsAndRecordsUnavailabilityAsAnEvent() {
        post("/api/resources/" + wheels + "/availability", """
                {"startsAt": "%s", "endsAt": "%s", "capacityDelta": -1, "reason": "Maintenance"}"""
                .formatted(at(10), at(40)));
        post("/api/resources/" + wheels + "/availability", """
                {"startsAt": "%s", "endsAt": "%s", "capacityDelta": 3, "reason": "Borrowed wheels"}"""
                .formatted(at(50), at(60)));

        assertThat(capacity(state(at(20)), "Wheels")).isEqualTo(1);
        assertThat(capacity(state(at(45)), "Wheels")).isEqualTo(2);
        assertThat(capacity(state(at(55)), "Wheels")).isEqualTo(5);

        JsonNode events = get("/api/timeline?type=RESOURCE_UNAVAILABLE").getBody();
        assertThat(events.path("totalItems").asLong()).isEqualTo(1);
        JsonNode event = events.path("items").get(0);
        assertThat(event.path("resourceName").asText()).isEqualTo("Wheels");
        assertThat(event.path("occurredAt").asText()).isEqualTo(at(10).toString());
        assertThat(event.path("durationSeconds").asLong()).isEqualTo(30 * 60);
        assertThat(event.path("payload").path("unitsUnavailable").asInt()).isEqualTo(1);
        assertThat(event.path("payload").path("reason").asText()).isEqualTo("Maintenance");
    }

    @Test
    void theOverviewSummarisesThePresent() {
        String done = placeOrder("DONE", 100);
        finish(batchOf(done), 10, true, "97");
        String working = placeOrder("WORKING", 50);
        append(batchOf(working), "STAGE_STARTED", at(100), "");
        post("/api/orders", order("LATE", 3, at(0).minus(10, ChronoUnit.DAYS), at(0).minus(2, ChronoUnit.DAYS),
                "{\"stoneCount\": 10, \"roughCarats\": \"12\"}"));

        JsonNode overview = get("/api/factories/" + factoryId + "/overview").getBody();

        JsonNode orders = overview.path("orders");
        assertThat(orders.path("total").asLong()).isEqualTo(3);
        assertThat(orders.path("active").asLong()).isEqualTo(2);
        assertThat(orders.path("pending").asLong()).isEqualTo(1);
        assertThat(orders.path("inProgress").asLong()).isEqualTo(1);
        assertThat(orders.path("completed").asLong()).isEqualTo(1);
        assertThat(orders.path("delayed").asLong()).isEqualTo(1);
        assertThat(overview.path("stages").get(0).path("inProgress").asInt()).isEqualTo(1);
        assertThat(overview.path("stages").get(0).path("queued").asInt()).isEqualTo(1);
        assertThat(overview.path("machines").path("inUse").asInt()).isEqualTo(1);
        assertThat(overview.path("machines").path("capacity").asInt()).isEqualTo(3);
        assertThat(overview.path("employees").path("inUse").asInt()).isZero();

        JsonNode output = overview.path("dailyOutput");
        assertThat(output).hasSize(14);
        long batchesFinished = 0;
        double stonesFinished = 0;
        for (JsonNode day : output) {
            batchesFinished += day.path("batchesCompleted").asLong();
            stonesFinished += day.path("stonesCompleted").asDouble();
        }
        assertThat(batchesFinished).isEqualTo(1);
        assertThat(stonesFinished).isEqualTo(97.0);
        assertThat(overview.path("recentEvents")).hasSize(10);
        assertThat(overview.path("recentEvents").get(0).path("orderNumber").asText()).isEqualTo("WORKING");
        assertThat(overview.path("source").asText()).isEqualTo("ACTUAL");
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /** {@code minutes} after the start of the test's three-hour window. */
    private Instant at(int minutes) {
        return base.plus(minutes, ChronoUnit.MINUTES);
    }

    private String order(String number, int priority, Instant placedAt, Instant dueAt, String batchesJson) {
        return """
                {"factoryId": "%s", "orderNumber": "%s", "priority": %d, "placedAt": "%s", "dueAt": %s,
                 "batches": [%s]}""".formatted(factoryId, number, priority, placedAt,
                dueAt == null ? "null" : "\"" + dueAt + "\"", batchesJson);
    }

    /** Places a one-batch order at the start of the window and returns its id. */
    private String placeOrder(String number, int stones) {
        ResponseEntity<JsonNode> response = post("/api/orders", order(number, 3, at(0), null,
                "{\"stoneCount\": %d, \"roughCarats\": \"%d\"}".formatted(stones, stones + 30)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asText();
    }

    private String batchOf(String orderId) {
        return get("/api/orders/" + orderId).getBody().path("batches").get(0).path("id").asText();
    }

    private ResponseEntity<JsonNode> append(String batchId, String type, Instant occurredAt, String extraFields) {
        return post("/api/events", """
                {"eventType": "%s", "batchId": "%s", "occurredAt": "%s"%s}"""
                .formatted(type, batchId, occurredAt, extraFields));
    }

    private void finish(String batchId, int startMinute) {
        finish(batchId, startMinute, true, "9");
    }

    /**
     * Walks a batch through both stages, ten minutes per step.
     *
     * @param fromQueue   false when cutting has already been started
     * @param finalStones stones that leave each stage
     */
    private void finish(String batchId, int startMinute, boolean fromQueue, String finalStones) {
        String output = ", \"stoneQuantity\": \"" + finalStones + "\", \"caratQuantity\": \"8\"";
        if (fromQueue) {
            assertCreated(append(batchId, "STAGE_STARTED", at(startMinute), ""));
        }
        assertCreated(append(batchId, "STAGE_COMPLETED", at(startMinute + 10), output));
        assertCreated(append(batchId, "STAGE_STARTED", at(startMinute + 20), ""));
        assertCreated(append(batchId, "STAGE_COMPLETED", at(startMinute + 30), output));
    }

    private static void assertCreated(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
    }

    private String orderStatus(String orderId) {
        return get("/api/orders/" + orderId).getBody().path("order").path("status").asText();
    }

    private JsonNode state(Instant at) {
        return get("/api/factories/" + factoryId + "/state?at=" + at).getBody();
    }

    private static int unitsInUse(JsonNode state, String poolName) {
        return pool(state, poolName).path("unitsInUse").asInt();
    }

    private static int capacity(JsonNode state, String poolName) {
        return pool(state, poolName).path("capacity").asInt();
    }

    private static JsonNode pool(JsonNode state, String poolName) {
        for (JsonNode resource : state.path("resources")) {
            if (resource.path("name").asText().equals(poolName)) {
                return resource;
            }
        }
        throw new AssertionError("No pool named " + poolName);
    }

    private String rowText(String eventId) {
        return jdbc.queryForObject("select e::text from operational_event e where id = ?::uuid", String.class,
                eventId);
    }

    private static void assertUnprocessable(ResponseEntity<JsonNode> response, String detailPart) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("detail").asText()).contains(detailPart);
    }
}
