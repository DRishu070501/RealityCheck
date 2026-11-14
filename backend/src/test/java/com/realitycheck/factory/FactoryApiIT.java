package com.realitycheck.factory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class FactoryApiIT extends IntegrationTest {

    private static final String TWO_DAY_FACTORY = """
            {"name": "Test Works", "description": "For tests", "timeZone": "Asia/Kolkata", "currencyCode": "INR",
             "calendar": {"shifts": [
               {"dayOfWeek": 2, "startTime": "14:00", "endTime": "18:00"},
               {"dayOfWeek": 1, "startTime": "09:00", "endTime": "18:00",
                "breaks": [{"startTime": "13:00", "endTime": "14:00"}]},
               {"dayOfWeek": 2, "startTime": "09:00", "endTime": "13:00"}]}}""";

    @Test
    void createsAFactoryAndReadsItBackWithAnOrderedCalendar() {
        ResponseEntity<JsonNode> created = post("/api/factories", TWO_DAY_FACTORY);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().path("id").asText();
        assertThat(created.getHeaders().getLocation()).hasPath("/api/factories/" + id);

        JsonNode factory = get("/api/factories/" + id).getBody();
        assertThat(factory.path("name").asText()).isEqualTo("Test Works");
        assertThat(factory.path("timeZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(factory.path("currencyCode").asText()).isEqualTo("INR");
        assertThat(factory.path("demo").asBoolean()).isFalse();
        assertThat(factory.path("createdAt").asText()).isEqualTo(created.getBody().path("createdAt").asText());

        JsonNode shifts = factory.path("calendar").path("shifts");
        assertThat(texts(shifts, "dayOfWeek")).containsExactly("1", "2", "2");
        assertThat(texts(shifts, "startTime")).containsExactly("09:00", "09:00", "14:00");
        assertThat(shifts.get(0).path("breaks").get(0).path("endTime").asText()).isEqualTo("14:00");
        // Monday 9h minus a 1h break, Tuesday 4h + 4h.
        assertThat(factory.path("calendar").path("weeklyWorkingSeconds").asLong()).isEqualTo(16 * 3600);
    }

    @Test
    void aNewFactoryHasNoWorkflowsOrResources() {
        String id = createFactory("Empty Works");

        JsonNode counts = get("/api/factories/" + id).getBody().path("counts");

        assertThat(counts.path("workflows").asLong()).isZero();
        assertThat(counts.path("resources").asLong()).isZero();
    }

    @Test
    void listsFactoriesByNameInPages() {
        createFactory("Charlie");
        createFactory("Alpha");
        createFactory("Bravo");

        JsonNode firstPage = get("/api/factories?size=2").getBody();
        JsonNode secondPage = get("/api/factories?size=2&page=1").getBody();

        assertThat(texts(firstPage.path("items"), "name")).containsExactly("Alpha", "Bravo");
        assertThat(firstPage.path("totalItems").asLong()).isEqualTo(3);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(2);
        assertThat(texts(secondPage.path("items"), "name")).containsExactly("Charlie");
    }

    @Test
    void rejectsANegativePage() {
        ResponseEntity<JsonNode> response = get("/api/factories?page=-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorFields(response)).containsExactly("page");
    }

    @Test
    void rejectsADuplicateNameRegardlessOfCase() {
        createFactory("Surat Works");

        ResponseEntity<JsonNode> response = post("/api/factories", TWO_DAY_FACTORY.replace("Test Works", "SURAT works"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.getBody().path("detail").asText()).contains("already exists");
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() {
        ResponseEntity<JsonNode> response = post("/api/factories", """
                {"name": " ", "timeZone": "Mars/Olympus", "currencyCode": "rupees",
                 "calendar": {"shifts": [{"dayOfWeek": 8, "startTime": "09:00", "endTime": "18:00"}]}}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("title").asText()).isEqualTo("Validation failed");
        assertThat(errorFields(response)).contains("name", "currencyCode", "calendar.shifts[0].dayOfWeek");
    }

    @Test
    void rejectsAnUnknownTimeZoneAndCalendarConflicts() {
        ResponseEntity<JsonNode> response = post("/api/factories", """
                {"name": "Bad Calendar", "timeZone": "Mars/Olympus", "currencyCode": "XQZ",
                 "calendar": {"shifts": [
                   {"dayOfWeek": 1, "startTime": "09:00", "endTime": "18:00",
                    "breaks": [{"startTime": "17:30", "endTime": "18:30"}]},
                   {"dayOfWeek": 1, "startTime": "17:00", "endTime": "22:00"}]}}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorFields(response)).containsExactlyInAnyOrder(
                "timeZone", "currencyCode", "calendar.shifts[0].breaks[0]", "calendar.shifts[1]");
        assertThat(get("/api/factories").getBody().path("totalItems").asLong()).isZero();
    }

    @Test
    void rejectsPropertiesItDoesNotKnow() {
        ResponseEntity<JsonNode> response = post("/api/factories",
                TWO_DAY_FACTORY.replace("\"name\"", "\"demo\": true, \"name\""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("title").asText()).isEqualTo("Malformed request");
    }

    @Test
    void anUnknownFactoryIsNotFound() {
        ResponseEntity<JsonNode> response = get("/api/factories/00000000-0000-0000-0000-000000000001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().path("detail").asText()).contains("was not found");
    }
}
