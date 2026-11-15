package com.realitycheck.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.scenario.ScenarioTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The AI endpoints end to end with the built-in template analyst (IntegrationTest pins it): real
 * runs, stored insights, and proof that generating an insight changes no simulation data.
 */
class AiApiIT extends ScenarioTestSupport {

    private static final String[] SIMULATION_TABLES = {"simulation_run", "simulation_stage_result",
            "simulation_resource_result", "simulation_event_result"};

    @Test
    void anInsightIsGeneratedFromAStoredRunStoredAndListed() {
        String runId = runToCompletion(createScenario("Current operations"));

        ResponseEntity<JsonNode> created = post("/api/ai/insights",
                "{\"runId\": \"%s\", \"type\": \"BOTTLENECK\"}".formatted(runId));

        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.OK);
        JsonNode insight = created.getBody();
        assertThat(insight.path("source").asText()).isEqualTo("AI_INTERPRETATION");
        assertThat(insight.path("provider").asText()).isEqualTo("template");
        assertThat(insight.path("model").isNull()).isTrue();
        assertThat(insight.path("fallback").asBoolean()).isFalse();
        assertThat(insight.path("content").path("summary").asText()).isNotBlank();
        assertThat(insight.path("content").path("recommendations")).isNotEmpty();
        JsonNode evidence = insight.path("evidence");
        assertThat(evidence).isNotEmpty();
        assertThat(evidence.get(0).path("source").asText()).isEqualTo("SIMULATED");
        assertThat(evidence.get(0).path("sourceRunId").asText()).isEqualTo(runId);
        assertThat(jdbc.queryForObject("select count(*) from insight_evidence", Integer.class))
                .isEqualTo(evidence.size());

        // Same run, type and evidence: the stored insight comes back.
        JsonNode again = post("/api/ai/insights", "{\"runId\": \"%s\", \"type\": \"BOTTLENECK\"}".formatted(runId))
                .getBody();
        assertThat(again.path("id").asText()).isEqualTo(insight.path("id").asText());
        JsonNode regenerated = post("/api/ai/insights",
                "{\"runId\": \"%s\", \"type\": \"BOTTLENECK\", \"regenerate\": true}".formatted(runId)).getBody();
        assertThat(regenerated.path("id").asText()).isNotEqualTo(insight.path("id").asText());

        JsonNode history = get("/api/ai/insights/" + runId).getBody();
        assertThat(history.path("totalItems").asInt()).isEqualTo(2);
        assertThat(history.path("items").get(0).path("id").asText()).isEqualTo(regenerated.path("id").asText());
        assertThat(get("/api/ai/insights/" + runId + "?type=EXPLANATION").getBody().path("totalItems").asInt())
                .isZero();
    }

    @Test
    void comparisonsAndQuestionsUseTheBaseline() {
        String baseline = runToCompletion(createScenario("Current operations"));
        String scenario = createScenario("More wheels");
        addChange(scenario, "RESOURCE_CAPACITY",
                "{\"resourceId\": \"%s\", \"mode\": \"DELTA\", \"value\": 1}".formatted(wheels));
        String runId = runToCompletion(scenario);

        JsonNode summary = post("/api/ai/insights", """
                {"runId": "%s", "baselineRunId": "%s", "type": "COMPARISON_SUMMARY"}""".formatted(runId, baseline))
                .getBody();
        assertThat(summary.path("baselineRunId").asText()).isEqualTo(baseline);
        assertThat(summary.path("content").path("summary").asText()).contains("Current operations", "More wheels");

        ResponseEntity<JsonNode> answer = post("/api/ai/ask", """
                {"runId": "%s", "baselineRunId": "%s", "question": "Where is the bottleneck?"}"""
                .formatted(runId, baseline));
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answer.getBody().path("type").asText()).isEqualTo("ANSWER");
        assertThat(answer.getBody().path("question").asText()).isEqualTo("Where is the bottleneck?");
    }

    @Test
    void generatingInsightsChangesNoSimulationData() {
        String runId = runToCompletion(createScenario("Current operations"));
        String before = fingerprint(SIMULATION_TABLES);

        for (String type : new String[] {"EXPLANATION", "BOTTLENECK", "RECOMMENDATIONS", "DELAY_ANALYSIS",
                "EXECUTIVE_REPORT"}) {
            assertThat(post("/api/ai/insights", "{\"runId\": \"%s\", \"type\": \"%s\"}".formatted(runId, type))
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        post("/api/ai/ask", "{\"runId\": \"%s\", \"question\": \"What does it cost?\"}".formatted(runId));

        assertThat(fingerprint(SIMULATION_TABLES)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from simulation_insight", Integer.class)).isEqualTo(6);
    }

    @Test
    void badRequestsAreRefusedBeforeAnyProviderIsCalled() {
        String runId = runToCompletion(createScenario("Current operations"));

        assertThat(post("/api/ai/insights", "{\"runId\": \"%s\", \"type\": \"COMPARISON_SUMMARY\"}".formatted(runId))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/ai/insights", "{\"type\": \"BOTTLENECK\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/ai/ask", "{\"runId\": \"%s\", \"question\": \"%s\"}".formatted(runId, "x".repeat(501)))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/ai/insights", "{\"runId\": \"%s\", \"type\": \"BOTTLENECK\"}"
                .formatted(UUID.randomUUID())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/ai/insights/" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from simulation_insight", Integer.class)).isZero();
    }

    @Test
    void aRunThatIsNotCompletedIsAConflict() {
        String runId = runToCompletion(createScenario("Current operations"));
        jdbc.execute("alter table simulation_run disable trigger user");
        try {
            jdbc.update("update simulation_run set status = 'FAILED' where id = ?::uuid", runId);
        } finally {
            jdbc.execute("alter table simulation_run enable trigger user");
        }

        ResponseEntity<JsonNode> response = post("/api/ai/insights",
                "{\"runId\": \"%s\", \"type\": \"BOTTLENECK\"}".formatted(runId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("status").asInt()).isEqualTo(409);
        assertThat(response.getBody().path("runStatus").asText()).isEqualTo("FAILED");
    }

    @Test
    void insightsAreDeletedWithTheirRunAndCannotBeEdited() {
        String runId = runToCompletion(createScenario("Current operations"));
        post("/api/ai/insights", "{\"runId\": \"%s\", \"type\": \"BOTTLENECK\"}".formatted(runId));

        try {
            jdbc.update("update simulation_insight set provider = 'edited'");
            throw new AssertionError("insights must be append-only");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertThat(expected.getMessage()).contains("append-only");
        }
        jdbc.update("delete from simulation_run where id = ?::uuid", runId);
        assertThat(jdbc.queryForObject("select count(*) from simulation_insight", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from insight_evidence", Integer.class)).isZero();
    }
}
