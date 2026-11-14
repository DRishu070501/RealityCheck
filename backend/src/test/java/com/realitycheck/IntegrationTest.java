package com.realitycheck;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that run the whole application against a real PostgreSQL. One container is
 * started for the test run and shared; every test starts from empty tables.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void emptyAllTables() {
        String tables = jdbc.queryForObject("""
                select string_agg(format('%I', table_name), ', ')
                from information_schema.tables
                where table_schema = current_schema()
                  and table_type = 'BASE TABLE'
                  and table_name <> 'flyway_schema_history'""", String.class);
        if (tables != null) {
            jdbc.execute("truncate table " + tables + " restart identity cascade");
        }
    }

    protected ResponseEntity<JsonNode> get(String path) {
        return rest.getForEntity(path, JsonNode.class);
    }

    protected ResponseEntity<JsonNode> post(String path, String json) {
        return rest.exchange(path, HttpMethod.POST, body(json), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> put(String path, String json) {
        return rest.exchange(path, HttpMethod.PUT, body(json), JsonNode.class);
    }

    /** The {@code field} values of a validation problem's {@code errors} array. */
    protected static List<String> errorFields(ResponseEntity<JsonNode> response) {
        return StreamSupport.stream(response.getBody().path("errors").spliterator(), false)
                .map(error -> error.path("field").asText())
                .toList();
    }

    protected static List<String> texts(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.path(field).asText()).toList();
    }

    /** Creates a factory open Monday 09:00 to 18:00 and returns its id. */
    protected String createFactory(String name) {
        ResponseEntity<JsonNode> response = post("/api/factories", """
                {"name": "%s", "timeZone": "Asia/Kolkata", "currencyCode": "INR",
                 "calendar": {"shifts": [{"dayOfWeek": 1, "startTime": "09:00", "endTime": "18:00"}]}}"""
                .formatted(name));
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Factory was not created: " + response);
        }
        return response.getBody().path("id").asText();
    }

    /** Creates a pool and returns its id. */
    protected String createPool(String factoryId, String kind, String name, int capacity) {
        ResponseEntity<JsonNode> response = post("/api/resources", """
                {"factoryId": "%s", "kind": "%s", "category": "TEST_CATEGORY", "name": "%s",
                 "capacity": %d, "hourlyCost": "100.00"}""".formatted(factoryId, kind, name, capacity));
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Pool was not created: " + response);
        }
        return response.getBody().path("id").asText();
    }

    private static HttpEntity<String> body(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }
}
