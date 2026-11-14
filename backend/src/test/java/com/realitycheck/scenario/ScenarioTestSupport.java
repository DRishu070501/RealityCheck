package com.realitycheck.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * A small factory built through the API, open every day 06:00-23:59 so simulations finish in
 * simulated hours: Cutting (one saw) then Polishing (one wheel and two polishers per slot), with
 * two wheels, four polishers and an unused spare machine. Two orders were placed two hours ago.
 */
public abstract class ScenarioTestSupport extends IntegrationTest {

    protected final Instant placedAt = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    protected final Instant fork = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

    protected String factoryId;
    protected String saws;
    protected String wheels;
    protected String polishers;
    protected String spare;
    protected String cutting;
    protected String polishing;

    @BeforeEach
    void createSmallFactory() {
        StringBuilder shifts = new StringBuilder();
        for (int day = 1; day <= 7; day++) {
            shifts.append(day > 1 ? "," : "")
                    .append("{\"dayOfWeek\": ").append(day).append(", \"startTime\": \"06:00\", \"endTime\": \"23:59\"}");
        }
        ResponseEntity<JsonNode> factory = post("/api/factories", """
                {"name": "Works", "timeZone": "Asia/Kolkata", "currencyCode": "INR",
                 "calendar": {"shifts": [%s]}}""".formatted(shifts));
        assertThat(factory.getStatusCode()).as(String.valueOf(factory.getBody())).isEqualTo(HttpStatus.CREATED);
        factoryId = factory.getBody().path("id").asText();
        saws = createPool(factoryId, "MACHINE", "Saws", 1);
        wheels = createPool(factoryId, "MACHINE", "Wheels", 2);
        polishers = createPool(factoryId, "EMPLOYEE", "Polishers", 4);
        spare = createPool(factoryId, "MACHINE", "Spare", 1);
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
        placeOrder("ORD-1", "{\"stoneCount\": 40, \"roughCarats\": \"52\"}, {\"stoneCount\": 30, \"roughCarats\": \"39\"}");
        placeOrder("ORD-2", "{\"stoneCount\": 20, \"roughCarats\": \"26\"}");
    }

    protected void placeOrder(String number, String batches) {
        ResponseEntity<JsonNode> order = post("/api/orders", """
                {"factoryId": "%s", "orderNumber": "%s", "priority": 3, "placedAt": "%s", "dueAt": "%s",
                 "batches": [%s]}""".formatted(factoryId, number, placedAt, placedAt.plus(1, ChronoUnit.DAYS),
                batches));
        assertThat(order.getStatusCode()).as(String.valueOf(order.getBody())).isEqualTo(HttpStatus.CREATED);
    }

    /** Creates a scenario at {@link #fork} and returns its id. */
    protected String createScenario(String name) {
        ResponseEntity<JsonNode> created = post("/api/scenarios", """
                {"factoryId": "%s", "name": "%s", "forkAt": "%s"}""".formatted(factoryId, name, fork));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        return created.getBody().path("id").asText();
    }

    protected ResponseEntity<JsonNode> addChange(String scenarioId, String changeType, String payload) {
        return post("/api/scenarios/" + scenarioId + "/changes",
                "{\"changeType\": \"%s\", \"payload\": %s}".formatted(changeType, payload));
    }

    /** The default client cannot send PATCH; the JDK's HttpClient can. */
    protected ResponseEntity<JsonNode> patch(String path, String json) {
        org.springframework.web.client.RestTemplate template = new org.springframework.web.client.RestTemplate(
                new org.springframework.http.client.JdkClientHttpRequestFactory());
        template.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        template.setMessageConverters(rest.getRestTemplate().getMessageConverters());
        return template.exchange(rest.getRootUri() + path, HttpMethod.PATCH, json(json, null), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> delete(String path) {
        return rest.exchange(path, HttpMethod.DELETE, null, JsonNode.class);
    }

    protected ResponseEntity<JsonNode> simulate(String scenarioId, String idempotencyKey, boolean force) {
        return rest.exchange("/api/scenarios/" + scenarioId + "/simulate?force=" + force, HttpMethod.POST,
                json("", idempotencyKey), JsonNode.class);
    }

    /** Submits a run and waits until it is no longer pending or running; returns the run id. */
    protected String runToCompletion(String scenarioId) {
        ResponseEntity<JsonNode> submitted = simulate(scenarioId, null, false);
        assertThat(submitted.getStatusCode()).as(String.valueOf(submitted.getBody())).isEqualTo(HttpStatus.ACCEPTED);
        String runId = submitted.getBody().path("runId").asText();
        assertThat(awaitFinished(runId).path("status").asText()).isEqualTo("COMPLETED");
        return runId;
    }

    protected JsonNode awaitFinished(String runId) {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode run = get("/api/simulations/" + runId).getBody();
            String status = run.path("status").asText();
            if (status.equals("COMPLETED") || status.equals("FAILED")) {
                return run;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("Run " + runId + " did not finish in time");
    }

    /** A checksum of every row of the given tables. */
    protected String fingerprint(String... tables) {
        StringBuilder sql = new StringBuilder("select md5(''");
        for (String table : tables) {
            sql.append(" || coalesce((select string_agg(t::text, '|' order by t::text) from ").append(table)
                    .append(" t), '')");
        }
        return jdbc.queryForObject(sql.append(")").toString(), String.class);
    }

    private static HttpEntity<String> json(String body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return new HttpEntity<>(body, headers);
    }
}
