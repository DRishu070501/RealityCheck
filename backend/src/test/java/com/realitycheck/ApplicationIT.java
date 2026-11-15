package com.realitycheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Milestone 1 acceptance: the application boots against a real PostgreSQL, migrations run,
 * health and system info answer, and the API documentation is served.
 */
class ApplicationIT extends IntegrationTest {

    @Test
    void healthIsUpAndHidesComponentDetails() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(response.getBody().has("components")).isFalse();
    }

    @Test
    void readinessProbeIsExposed() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/actuator/health/readiness", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() {
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/actuator/beans", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void systemInfoReportsTheLiveDatabaseAndAppliedMigrations() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/system/info", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.path("application").asText()).isEqualTo("RealityCheck");
        assertThat(body.path("serverTime").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(body.path("database").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("database").path("product").asText()).isEqualTo("PostgreSQL");
        assertThat(body.path("database").path("version").asText()).startsWith("17");
        assertThat(body.path("migrations").path("appliedCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.path("migrations").path("currentVersion").asText()).isNotBlank();
        assertThat(body.path("ai").path("enabled").asBoolean()).isFalse();
        assertThat(body.path("ai").path("provider").asText()).isEqualTo("template");
        assertThat(body.path("ai").path("available").asBoolean()).isTrue();
        assertThat(body.toString()).doesNotContainIgnoringCase("apiKey");
    }

    @Test
    void migrationHistoryMatchesWhatSystemInfoReports() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        JsonNode body = rest.getForObject("/api/system/info", JsonNode.class);

        assertThat(body.path("migrations").path("appliedCount").asInt()).isEqualTo(applied);
    }

    @Test
    void appendOnlyTriggerFunctionRejectsUpdatesAndDeletes() {
        jdbc.execute("create table append_only_probe (id int primary key, note text)");
        jdbc.execute("""
                create trigger append_only_probe_guard before update or delete on append_only_probe
                for each row execute function reject_modification()""");
        jdbc.update("insert into append_only_probe values (1, 'original')");
        try {
            assertThatThrownBy(() -> jdbc.update("update append_only_probe set note = 'rewritten' where id = 1"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbc.update("delete from append_only_probe where id = 1"))
                    .hasMessageContaining("append-only");
            assertThat(jdbc.queryForObject("select note from append_only_probe where id = 1", String.class))
                    .isEqualTo("original");
        } finally {
            jdbc.execute("drop table append_only_probe");
        }
    }

    @Test
    void openApiDocumentDescribesTheApi() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/v3/api-docs", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("info").path("title").asText()).isEqualTo("RealityCheck API");
        assertThat(response.getBody().path("paths").has("/api/system/info")).isTrue();
    }

    @Test
    void swaggerUiIsServed() {
        ResponseEntity<String> response = rest.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownApiPathIsAProblemDocumentNotAWhitelabelPage() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/does-not-exist", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(404);
        assertThat(response.getBody().path("detail").asText()).isEqualTo("No endpoint matches this path.");
        assertThat(response.getBody().has("trace")).isFalse();
    }

    @Test
    void demoResetIsOffUnlessConfigured() {
        assertThat(post("/api/demo/reset", "{}").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/system/info").getBody().path("demo").path("resetEnabled").asBoolean()).isFalse();
    }

    @Test
    void nothingIsSeededUnlessConfigured() {
        assertThat(get("/api/factories").getBody().path("totalItems").asLong()).isZero();
    }

    @Test
    void corsAllowsTheConfiguredOriginOnly() {
        assertThat(preflight("http://localhost:5173").getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:5173");
        // The Docker frontend's own origin: browsers send Origin on writes even through its proxy.
        assertThat(preflight("http://localhost:3000").getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:3000");

        ResponseEntity<String> rejected = preflight("https://evil.example");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    private ResponseEntity<String> preflight(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.GET);
        return rest.exchange("/api/system/info", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }
}
