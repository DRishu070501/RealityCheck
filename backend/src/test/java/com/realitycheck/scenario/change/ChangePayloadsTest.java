package com.realitycheck.scenario.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.common.error.FieldViolation;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.scenario.change.ChangePayload.ResourceCapacity;
import com.realitycheck.scenario.change.ChangePayload.WorkingSchedule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Payload parsing and field rules per change type (spec 06, design "Change payloads"). */
class ChangePayloadsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant FORK = Instant.parse("2026-01-12T05:30:00Z");
    private static final String POOL = "00000000-0000-0000-0000-000000000001";

    @Test
    void validPayloadsOfEveryTypeParseAndRoundTrip() throws Exception {
        String[][] cases = {
                {"RESOURCE_CAPACITY", "{\"resourceId\":\"" + POOL + "\",\"mode\":\"DELTA\",\"value\":2}"},
                {"RESOURCE_UNAVAILABLE", "{\"resourceId\":\"" + POOL + "\",\"units\":5}"},
                {"RESOURCE_DOWNTIME", "{\"resourceId\":\"" + POOL
                        + "\",\"from\":\"2026-01-13T04:00:00Z\",\"to\":\"2026-01-13T08:00:00Z\"}"},
                {"DEMAND_VOLUME", "{\"percent\":20}"},
                {"PROCESSING_RATE", "{\"stageId\":\"" + POOL + "\",\"mode\":\"PERCENT\",\"value\":\"-12.5\"}"},
                {"WORKING_SCHEDULE", "{\"mode\":\"ADD\",\"shifts\":[{\"dayOfWeek\":1,\"startTime\":\"18:00\","
                        + "\"endTime\":\"22:00\",\"breaks\":[{\"startTime\":\"20:00\",\"endTime\":\"20:30\"}]}]}"}};
        for (String[] c : cases) {
            ChangeType type = ChangeType.valueOf(c[0]);
            ChangePayload parsed = ChangePayloads.parseValid(type, JSON.readTree(c[1]), FORK);

            assertThat(parsed.type()).isEqualTo(type);
            JsonNode written = ChangePayloads.toJson(parsed);
            assertThat(ChangePayloads.parse(type, written)).as(c[0]).isEqualTo(parsed);
        }
    }

    @Test
    void scheduleTimesAreWrittenAsHoursAndMinutes() throws Exception {
        WorkingSchedule parsed = (WorkingSchedule) ChangePayloads.parse(ChangeType.WORKING_SCHEDULE, JSON.readTree(
                "{\"mode\":\"REPLACE\",\"shifts\":[{\"dayOfWeek\":2,\"startTime\":\"08:30\",\"endTime\":\"17:00\"}]}"));

        assertThat(ChangePayloads.toJson(parsed).path("shifts").get(0).path("startTime").asText()).isEqualTo("08:30");
    }

    @Test
    void anUnknownFieldOrWrongTypeNamesThePayloadField() throws Exception {
        assertThatThrownBy(() -> ChangePayloads.parse(ChangeType.DEMAND_VOLUME,
                JSON.readTree("{\"percent\":20,\"pct\":1}")))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(fields(ex)).containsExactly("payload.pct"));
        assertThatThrownBy(() -> ChangePayloads.parse(ChangeType.RESOURCE_CAPACITY,
                JSON.readTree("{\"resourceId\":\"not-a-uuid\",\"mode\":\"DELTA\",\"value\":1}")))
                .satisfies(ex -> assertThat(fields(ex)).containsExactly("payload.resourceId"));
        assertThatThrownBy(() -> ChangePayloads.parse(ChangeType.RESOURCE_CAPACITY,
                JSON.readTree("{\"resourceId\":\"" + POOL + "\",\"mode\":\"TRIPLE\",\"value\":1}")))
                .satisfies(ex -> assertThat(fields(ex)).containsExactly("payload.mode"));
        assertThatThrownBy(() -> ChangePayloads.parse(ChangeType.DEMAND_VOLUME, JSON.readTree("[1]")))
                .satisfies(ex -> assertThat(fields(ex)).containsExactly("payload"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "RESOURCE_CAPACITY|{\"resourceId\":\"" + POOL + "\",\"mode\":\"ABSOLUTE\",\"value\":-1}|payload.value",
            "RESOURCE_CAPACITY|{\"resourceId\":\"" + POOL + "\",\"mode\":\"ABSOLUTE\",\"value\":2.5}|payload.value",
            "RESOURCE_CAPACITY|{\"resourceId\":\"" + POOL + "\",\"mode\":\"DELTA\",\"value\":0}|payload.value",
            "RESOURCE_CAPACITY|{\"resourceId\":\"" + POOL + "\",\"mode\":\"PERCENT\",\"value\":-101}|payload.value",
            "RESOURCE_CAPACITY|{\"mode\":\"DELTA\",\"value\":1}|payload.resourceId",
            "RESOURCE_UNAVAILABLE|{\"resourceId\":\"" + POOL + "\",\"units\":0}|payload.units",
            "RESOURCE_UNAVAILABLE|{\"resourceId\":\"" + POOL + "\",\"units\":1,\"to\":\"2026-01-12T05:00:00Z\"}|payload.to",
            "RESOURCE_DOWNTIME|{\"resourceId\":\"" + POOL
                    + "\",\"from\":\"2026-01-13T08:00:00Z\",\"to\":\"2026-01-13T04:00:00Z\"}|payload.to",
            "RESOURCE_DOWNTIME|{\"resourceId\":\"" + POOL + "\",\"to\":\"2026-01-13T04:00:00Z\"}|payload.from",
            "DEMAND_VOLUME|{\"percent\":-100}|payload.percent",
            "DEMAND_VOLUME|{\"percent\":0}|payload.percent",
            "DEMAND_VOLUME|{\"percent\":501}|payload.percent",
            "PROCESSING_RATE|{\"stageId\":\"" + POOL + "\",\"mode\":\"ABSOLUTE\",\"value\":0}|payload.value",
            "PROCESSING_RATE|{\"stageId\":\"" + POOL + "\",\"mode\":\"PERCENT\",\"value\":-100}|payload.value",
            "WORKING_SCHEDULE|{\"mode\":\"REPLACE\",\"shifts\":[]}|payload.shifts",
            "WORKING_SCHEDULE|{\"mode\":\"ADD\",\"shifts\":[{\"dayOfWeek\":8,\"startTime\":\"09:00\",\"endTime\":\"10:00\"}]}|payload.shifts[0].dayOfWeek",
            "WORKING_SCHEDULE|{\"mode\":\"ADD\",\"shifts\":[{\"dayOfWeek\":1,\"startTime\":\"10:00\",\"endTime\":\"09:00\"}]}|payload.shifts[0].endTime",
    })
    void invalidPayloadsNameTheField(String type, String payload, String field) throws Exception {
        assertThatThrownBy(() -> ChangePayloads.parseValid(ChangeType.valueOf(type), JSON.readTree(payload), FORK))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(fields(ex)).contains(field));
    }

    @Test
    void overlappingShiftsWithinOnePayloadAreRejected() throws Exception {
        assertThatThrownBy(() -> ChangePayloads.parseValid(ChangeType.WORKING_SCHEDULE, JSON.readTree("""
                {"mode": "REPLACE", "shifts": [
                  {"dayOfWeek": 1, "startTime": "09:00", "endTime": "13:00"},
                  {"dayOfWeek": 1, "startTime": "12:00", "endTime": "18:00"}]}"""), FORK))
                .satisfies(ex -> assertThat(fields(ex)).containsExactly("payload.shifts"));
    }

    @Test
    void wholeNumbersWrittenWithDecimalsAreAcceptedForUnits() {
        ResourceCapacity capacity = new ResourceCapacity(java.util.UUID.fromString(POOL),
                ChangePayload.CapacityMode.ABSOLUTE, new java.math.BigDecimal("3.00"));

        assertThat(ChangePayloads.validate(capacity, FORK)).isEmpty();
    }

    private static List<String> fields(Throwable ex) {
        return ((ValidationException) ex).errors().stream().map(FieldViolation::field).toList();
    }
}
