package com.realitycheck.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ResourceApiIT extends IntegrationTest {

    @Test
    void createsAPoolAndReadsItBack() {
        String factoryId = createFactory("Works");

        ResponseEntity<JsonNode> created = post("/api/resources", """
                {"factoryId": "%s", "kind": "MACHINE", "category": "POLISHING_WHEEL",
                 "name": "Polishing wheels", "capacity": 6, "hourlyCost": 140.5}""".formatted(factoryId));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode pool = get(created.getHeaders().getLocation().getPath()).getBody();
        assertThat(pool.path("kind").asText()).isEqualTo("MACHINE");
        assertThat(pool.path("capacity").asInt()).isEqualTo(6);
        assertThat(pool.path("hourlyCost").isTextual()).isTrue();
        assertThat(pool.path("hourlyCost").asText()).isEqualTo("140.50");
        assertThat(pool.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(pool.path("version").asLong()).isZero();
        assertThat(pool.path("requiredByStages")).isEmpty();
    }

    @Test
    void listsMachinesThenEmployeesByNameAndFiltersByKind() {
        String factoryId = createFactory("Works");
        createPool(factoryId, "EMPLOYEE", "Polishers", 12);
        createPool(factoryId, "MACHINE", "Wheels", 6);
        createPool(factoryId, "MACHINE", "Saws", 4);
        createPool(createFactory("Other Works"), "MACHINE", "Elsewhere", 1);

        JsonNode all = get("/api/resources?factoryId=" + factoryId).getBody();
        JsonNode machines = get("/api/resources?factoryId=" + factoryId + "&kind=MACHINE").getBody();

        assertThat(texts(all.path("items"), "name")).containsExactly("Saws", "Wheels", "Polishers");
        assertThat(texts(machines.path("items"), "name")).containsExactly("Saws", "Wheels");
        assertThat(machines.path("totalItems").asLong()).isEqualTo(2);
    }

    @Test
    void factoryIdMayBeOmittedOnlyWhileThereIsExactlyOneFactory() {
        assertThat(errorFields(get("/api/resources"))).containsExactly("factoryId");

        String factoryId = createFactory("Only Works");
        createPool(factoryId, "MACHINE", "Wheels", 6);
        assertThat(get("/api/resources").getBody().path("totalItems").asLong()).isEqualTo(1);

        createFactory("Second Works");
        ResponseEntity<JsonNode> ambiguous = get("/api/resources");
        assertThat(ambiguous.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorFields(ambiguous)).containsExactly("factoryId");
    }

    @Test
    void rejectsInvalidPools() {
        String factoryId = createFactory("Works");

        ResponseEntity<JsonNode> response = post("/api/resources", """
                {"factoryId": "%s", "kind": "MACHINE", "category": "polishing wheel",
                 "name": "Wheels", "capacity": -1, "hourlyCost": "-5.00"}""".formatted(factoryId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorFields(response)).containsExactly("capacity", "category", "hourlyCost");
    }

    @Test
    void rejectsADuplicateNameInTheSameFactoryOnly() {
        String factoryId = createFactory("Works");
        createPool(factoryId, "MACHINE", "Wheels", 6);

        ResponseEntity<JsonNode> duplicate = post("/api/resources", """
                {"factoryId": "%s", "kind": "EMPLOYEE", "category": "ANY", "name": "wheels",
                 "capacity": 1, "hourlyCost": "1.00"}""".formatted(factoryId));

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        createPool(createFactory("Other Works"), "MACHINE", "Wheels", 6);
    }

    @Test
    void updatesAPoolAndAdvancesItsVersion() {
        String id = createPool(createFactory("Works"), "MACHINE", "Wheels", 6);

        ResponseEntity<JsonNode> updated = put("/api/resources/" + id, """
                {"name": "Polishing wheels", "category": "POLISHING_WHEEL", "capacity": 8,
                 "hourlyCost": "150.00", "status": "ACTIVE", "version": 0}""");

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().path("capacity").asInt()).isEqualTo(8);
        assertThat(updated.getBody().path("version").asLong()).isEqualTo(1);
        JsonNode reread = get("/api/resources/" + id).getBody();
        assertThat(reread.path("name").asText()).isEqualTo("Polishing wheels");
        assertThat(reread.path("kind").asText()).isEqualTo("MACHINE");
    }

    @Test
    void refusesAnUpdateMadeFromAStaleVersion() {
        String id = createPool(createFactory("Works"), "MACHINE", "Wheels", 6);
        String body = """
                {"name": "Wheels", "category": "TEST_CATEGORY", "capacity": %d,
                 "hourlyCost": "100.00", "status": "ACTIVE", "version": 0}""";
        assertThat(put("/api/resources/" + id, body.formatted(7)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> stale = put("/api/resources/" + id, body.formatted(9));

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().path("code").asText()).isEqualTo("STALE_VERSION");
        assertThat(get("/api/resources/" + id).getBody().path("capacity").asInt()).isEqualTo(7);
    }

    @Test
    void theKindOfAPoolCannotBeChanged() {
        String id = createPool(createFactory("Works"), "MACHINE", "Wheels", 6);

        ResponseEntity<JsonNode> response = put("/api/resources/" + id, """
                {"name": "Wheels", "category": "TEST_CATEGORY", "capacity": 6, "kind": "EMPLOYEE",
                 "hourlyCost": "100.00", "status": "ACTIVE", "version": 0}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/resources/" + id).getBody().path("kind").asText()).isEqualTo("MACHINE");
    }

    @Test
    void recordsDowntimeAndReportsEffectiveCapacity() {
        String id = createPool(createFactory("Works"), "MACHINE", "Wheels", 6);

        ResponseEntity<JsonNode> window = post("/api/resources/" + id + "/availability", """
                {"startsAt": "2026-01-07T03:30:00Z", "endsAt": "2026-01-07T12:30:00Z",
                 "capacityDelta": -2, "reason": "Maintenance"}""");
        assertThat(window.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode ranged = get("/api/resources/" + id
                + "/availability?from=2026-01-07T00:00:00Z&to=2026-01-08T00:00:00Z").getBody();
        assertThat(ranged.path("baseCapacity").asInt()).isEqualTo(6);
        assertThat(ranged.path("windows")).hasSize(1);
        assertThat(texts(ranged.path("timeline"), "capacity")).containsExactly("6", "4", "6");
        assertThat(ranged.path("timeline").get(1).path("from").asText()).isEqualTo("2026-01-07T03:30:00Z");
        assertThat(ranged.path("timeline").get(1).path("to").asText()).isEqualTo("2026-01-07T12:30:00Z");

        JsonNode unranged = get("/api/resources/" + id + "/availability").getBody();
        assertThat(texts(unranged.path("timeline"), "capacity")).containsExactly("4");

        JsonNode elsewhere = get("/api/resources/" + id
                + "/availability?from=2026-02-01T00:00:00Z&to=2026-02-02T00:00:00Z").getBody();
        assertThat(elsewhere.path("windows")).isEmpty();
        assertThat(texts(elsewhere.path("timeline"), "capacity")).containsExactly("6");
    }

    @Test
    void aPoolWithoutWindowsHasAnEmptyTimelineUntilARangeIsGiven() {
        String id = createPool(createFactory("Works"), "MACHINE", "Wheels", 6);

        JsonNode body = get("/api/resources/" + id + "/availability").getBody();

        assertThat(body.path("windows")).isEmpty();
        assertThat(body.path("timeline")).isEmpty();
    }

    @Test
    void rejectsInvalidWindows() {
        String id = createPool(createFactory("Works"), "MACHINE", "Wheels", 6);
        String path = "/api/resources/" + id + "/availability";

        assertThat(errorFields(post(path, """
                {"startsAt": "2026-01-07T12:00:00Z", "endsAt": "2026-01-07T03:00:00Z",
                 "capacityDelta": -2, "reason": "Backwards"}"""))).containsExactly("endsAt");
        assertThat(errorFields(post(path, """
                {"startsAt": "2026-01-07T03:00:00Z", "endsAt": "2026-01-07T12:00:00Z",
                 "capacityDelta": 0, "reason": "Nothing"}"""))).containsExactly("capacityDelta");
        assertThat(errorFields(get(path + "?from=2026-01-08T00:00:00Z&to=2026-01-07T00:00:00Z")))
                .containsExactly("to");
        assertThat(post("/api/resources/00000000-0000-0000-0000-000000000001/availability", """
                {"startsAt": "2026-01-07T03:00:00Z", "endsAt": "2026-01-07T12:00:00Z",
                 "capacityDelta": -1, "reason": "No such pool"}""").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theDatabaseItselfRejectsInvalidRows() {
        String factoryId = createFactory("Works");
        String id = createPool(factoryId, "MACHINE", "Wheels", 6);

        assertThatThrownBy(() -> jdbc.update("update resource set capacity = -1 where id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update resource set kind = 'ROBOT' where id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into resource_availability (id, resource_id, starts_at, ends_at, capacity_delta, reason, created_at)
                values (gen_random_uuid(), ?::uuid, now(), now() - interval '1 hour', -1, 'inverted', now())""", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
