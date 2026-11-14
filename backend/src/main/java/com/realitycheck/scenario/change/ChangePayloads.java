package com.realitycheck.scenario.change;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.realitycheck.common.error.FieldViolation;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.scenario.change.ChangePayload.Break;
import com.realitycheck.scenario.change.ChangePayload.CapacityMode;
import com.realitycheck.scenario.change.ChangePayload.DemandVolume;
import com.realitycheck.scenario.change.ChangePayload.ProcessingRate;
import com.realitycheck.scenario.change.ChangePayload.RateMode;
import com.realitycheck.scenario.change.ChangePayload.ResourceCapacity;
import com.realitycheck.scenario.change.ChangePayload.ResourceDowntime;
import com.realitycheck.scenario.change.ChangePayload.ResourceUnavailable;
import com.realitycheck.scenario.change.ChangePayload.Shift;
import com.realitycheck.scenario.change.ChangePayload.WorkingSchedule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JSON to typed payload and back, and the field rules of each change type. Pure: no database. The
 * rules that need the factory (does the pool exist, does an added shift overlap an existing one)
 * are checked when the change is applied.
 */
public final class ChangePayloads {

    /** Strict: an unknown or misspelt field is an error rather than silently ignored. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            // Keeps 20 as 20, not 2E+1: payloads must round-trip exactly.
            .nodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private static final BigDecimal MINUS_100 = BigDecimal.valueOf(-100);
    private static final BigDecimal MAX_DEMAND_PERCENT = BigDecimal.valueOf(500);
    private static final int MAX_SHIFTS = 42;
    private static final int MAX_BREAKS = 8;

    private ChangePayloads() {
    }

    /**
     * @throws ValidationException naming the payload field when the JSON does not fit the type
     */
    public static ChangePayload parse(ChangeType type, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new ValidationException("payload", "must be a JSON object");
        }
        try {
            return MAPPER.treeToValue(payload, type.payloadClass());
        } catch (JsonMappingException ex) {
            String path = ex.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? "." + ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining());
            throw new ValidationException("payload" + path, path.isEmpty() ? "is not valid for " + type
                    : "has an invalid value or is not a field of " + type);
        } catch (JsonProcessingException ex) {
            throw new ValidationException("payload", "is not valid for " + type);
        }
    }

    public static JsonNode toJson(ChangePayload payload) {
        return MAPPER.valueToTree(payload);
    }

    /** Parses and validates in one step; throws on the first kind of failure with every field problem. */
    public static ChangePayload parseValid(ChangeType type, JsonNode payload, Instant forkAt) {
        ChangePayload parsed = parse(type, payload);
        List<FieldViolation> errors = validate(parsed, forkAt);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return parsed;
    }

    /**
     * The field rules of design.md's payload table. Field names are prefixed with {@code payload.}.
     *
     * @param forkAt windows must end after it, or they could not affect the simulation
     */
    public static List<FieldViolation> validate(ChangePayload payload, Instant forkAt) {
        List<FieldViolation> errors = new ArrayList<>();
        switch (payload) {
            case ResourceCapacity c -> {
                required(c.resourceId(), "resourceId", errors);
                required(c.mode(), "mode", errors);
                required(c.value(), "value", errors);
                if (c.mode() != null && c.value() != null) {
                    BigDecimal v = c.value();
                    switch (c.mode()) {
                        case ABSOLUTE -> {
                            if (!isInteger(v) || v.signum() < 0) {
                                errors.add(field("value", "must be a whole number of units, zero or more"));
                            }
                        }
                        case DELTA -> {
                            if (!isInteger(v) || v.signum() == 0) {
                                errors.add(field("value", "must be a whole, non-zero number of units"));
                            }
                        }
                        case PERCENT -> {
                            if (v.compareTo(MINUS_100) < 0 || v.signum() == 0) {
                                errors.add(field("value", "must be a non-zero percentage of -100 or more"));
                            }
                        }
                    }
                    if (c.mode() != CapacityMode.PERCENT && isInteger(v) && v.abs().compareTo(BigDecimal.valueOf(100_000)) > 0) {
                        errors.add(field("value", "must be at most 100000 units"));
                    }
                }
            }
            case ResourceUnavailable u -> {
                required(u.resourceId(), "resourceId", errors);
                if (u.units() == null) {
                    errors.add(field("units", "is required"));
                } else if (u.units() < 1) {
                    errors.add(field("units", "must be at least 1"));
                }
                window(u.from(), u.to(), forkAt, errors);
            }
            case ResourceDowntime d -> {
                required(d.resourceId(), "resourceId", errors);
                required(d.from(), "from", errors);
                required(d.to(), "to", errors);
                window(d.from(), d.to(), forkAt, errors);
            }
            case DemandVolume d -> {
                if (d.percent() == null) {
                    errors.add(field("percent", "is required"));
                } else if (d.percent().compareTo(MINUS_100) <= 0 || d.percent().signum() == 0
                        || d.percent().compareTo(MAX_DEMAND_PERCENT) > 0) {
                    errors.add(field("percent", "must be non-zero, greater than -100 and at most 500"));
                }
            }
            case ProcessingRate r -> {
                required(r.stageId(), "stageId", errors);
                required(r.mode(), "mode", errors);
                required(r.value(), "value", errors);
                if (r.mode() == RateMode.ABSOLUTE && r.value() != null && r.value().signum() <= 0) {
                    errors.add(field("value", "must be greater than zero"));
                }
                if (r.mode() == RateMode.PERCENT && r.value() != null
                        && (r.value().compareTo(MINUS_100) <= 0 || r.value().signum() == 0)) {
                    errors.add(field("value", "must be a non-zero percentage greater than -100"));
                }
            }
            case WorkingSchedule s -> {
                required(s.mode(), "mode", errors);
                if (s.shifts() == null || s.shifts().isEmpty()) {
                    errors.add(field("shifts", "must contain at least one shift"));
                } else if (s.shifts().size() > MAX_SHIFTS) {
                    errors.add(field("shifts", "must contain at most " + MAX_SHIFTS + " shifts"));
                } else {
                    shifts(s.shifts(), errors);
                }
            }
        }
        return errors;
    }

    /** Overlap between shifts of the same day, as an error message, or null when none overlap. */
    public static String overlap(List<Shift> shifts) {
        List<Shift> sorted = shifts.stream()
                .sorted(Comparator.comparing(Shift::dayOfWeek).thenComparing(Shift::startTime))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            Shift previous = sorted.get(i - 1);
            Shift current = sorted.get(i);
            if (previous.dayOfWeek().equals(current.dayOfWeek()) && current.startTime().isBefore(previous.endTime())) {
                return "the shift " + current.startTime() + "-" + current.endTime() + " on day " + current.dayOfWeek()
                        + " overlaps " + previous.startTime() + "-" + previous.endTime();
            }
        }
        return null;
    }

    private static void shifts(List<Shift> shifts, List<FieldViolation> errors) {
        boolean wellFormed = true;
        for (int i = 0; i < shifts.size(); i++) {
            Shift shift = shifts.get(i);
            String path = "shifts[" + i + "]";
            if (shift == null) {
                errors.add(field(path, "is required"));
                wellFormed = false;
                continue;
            }
            if (shift.dayOfWeek() == null || shift.dayOfWeek() < 1 || shift.dayOfWeek() > 7) {
                errors.add(field(path + ".dayOfWeek", "must be 1 (Monday) to 7 (Sunday)"));
                wellFormed = false;
            }
            if (shift.startTime() == null || shift.endTime() == null) {
                errors.add(field(path, "needs startTime and endTime"));
                wellFormed = false;
                continue;
            }
            if (!shift.endTime().isAfter(shift.startTime())) {
                errors.add(field(path + ".endTime", "must be after startTime"));
                wellFormed = false;
                continue;
            }
            List<Break> breaks = shift.breaksOrEmpty();
            if (breaks.size() > MAX_BREAKS) {
                errors.add(field(path + ".breaks", "must contain at most " + MAX_BREAKS + " breaks"));
            }
            for (int j = 0; j < breaks.size(); j++) {
                Break pause = breaks.get(j);
                String breakPath = path + ".breaks[" + j + "]";
                if (pause == null || pause.startTime() == null || pause.endTime() == null) {
                    errors.add(field(breakPath, "needs startTime and endTime"));
                } else if (!pause.endTime().isAfter(pause.startTime())) {
                    errors.add(field(breakPath + ".endTime", "must be after startTime"));
                } else if (pause.startTime().isBefore(shift.startTime()) || pause.endTime().isAfter(shift.endTime())) {
                    errors.add(field(breakPath, "must be inside the shift"));
                }
            }
            List<Break> ordered = breaks.stream()
                    .filter(b -> b != null && b.startTime() != null && b.endTime() != null)
                    .sorted(Comparator.comparing(Break::startTime))
                    .toList();
            for (int j = 1; j < ordered.size(); j++) {
                if (ordered.get(j).startTime().isBefore(ordered.get(j - 1).endTime())) {
                    errors.add(field(path + ".breaks", "must not overlap each other"));
                    break;
                }
            }
        }
        if (wellFormed) {
            String overlap = overlap(shifts);
            if (overlap != null) {
                errors.add(field("shifts", "must not overlap: " + overlap));
            }
        }
    }

    private static void window(Instant from, Instant to, Instant forkAt, List<FieldViolation> errors) {
        if (from != null && to != null && !to.isAfter(from)) {
            errors.add(field("to", "must be after from"));
        }
        if (to != null && forkAt != null && !to.isAfter(forkAt)) {
            errors.add(field("to", "must be after the scenario's fork time, or it could not affect the simulation"));
        }
    }

    private static void required(Object value, String name, List<FieldViolation> errors) {
        if (value == null) {
            errors.add(field(name, "is required"));
        }
    }

    private static boolean isInteger(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    private static FieldViolation field(String name, String message) {
        return new FieldViolation("payload." + name, message);
    }
}
