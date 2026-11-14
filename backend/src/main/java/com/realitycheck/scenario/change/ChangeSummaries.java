package com.realitycheck.scenario.change;

import com.realitycheck.scenario.change.ChangePayload.DemandVolume;
import com.realitycheck.scenario.change.ChangePayload.ProcessingRate;
import com.realitycheck.scenario.change.ChangePayload.ResourceCapacity;
import com.realitycheck.scenario.change.ChangePayload.ResourceDowntime;
import com.realitycheck.scenario.change.ChangePayload.ResourceUnavailable;
import com.realitycheck.scenario.change.ChangePayload.ScheduleMode;
import com.realitycheck.scenario.change.ChangePayload.Shift;
import com.realitycheck.scenario.change.ChangePayload.WorkingSchedule;
import com.realitycheck.scenario.snapshot.Snapshot;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Plain-language descriptions of changes, built from the payload and the names in a snapshot. They
 * describe the decision only and never contain a simulated number.
 */
public final class ChangeSummaries {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

    private final Snapshot snapshot;
    private final ZoneId zone;

    public ChangeSummaries(Snapshot snapshot) {
        this.snapshot = snapshot;
        this.zone = ZoneId.of(snapshot.timeZone());
    }

    String describe(ResourceCapacity c, int before, int after) {
        String pool = poolName(c.resourceId());
        String what = switch (c.mode()) {
            case ABSOLUTE -> "capacity set to " + units(after);
            case DELTA -> "capacity " + signed(c.value()) + " " + (c.value().abs().intValue() == 1 ? "unit" : "units");
            case PERCENT -> "capacity " + signed(c.value()) + "% (rounded down)";
        };
        return pool + ": " + what + ", " + before + " to " + after + " units";
    }

    String describe(ResourceUnavailable u) {
        String period = u.from() == null && u.to() == null ? "from the fork onwards"
                : u.from() == null ? "from the fork until " + when(u.to())
                : u.to() == null ? "from " + when(u.from()) + " onwards"
                : "from " + when(u.from()) + " to " + when(u.to());
        return poolName(u.resourceId()) + ": " + units(u.units()) + " unavailable " + period;
    }

    String describe(ResourceDowntime d) {
        return poolName(d.resourceId()) + ": every unit down from " + when(d.from()) + " to " + when(d.to());
    }

    String describe(DemandVolume d) {
        return "Demand " + signed(d.percent()) + "%: stones and carats of every batch not yet started";
    }

    String describe(ProcessingRate r, BigDecimal before, BigDecimal after) {
        String stage = snapshot.stages().stream().filter(s -> s.id().equals(r.stageId()))
                .map(Snapshot.StageSnapshot::name).findFirst().orElse("Unknown stage");
        String what = switch (r.mode()) {
            case ABSOLUTE -> "rate set to " + plain(after) + " stones per hour per slot";
            case PERCENT -> "rate " + signed(r.value()) + "%";
        };
        return stage + ": " + what + ", " + plain(before) + " to " + plain(after);
    }

    String describe(WorkingSchedule w) {
        String shifts = describeShifts(w.shifts());
        return (w.mode() == ScheduleMode.ADD ? "Add shifts: " : "Replace the working schedule with: ") + shifts;
    }

    /** Groups days that share the same hours: "Mon, Tue, Wed 18:00-22:00". */
    static String describeShifts(List<Shift> shifts) {
        Map<String, List<Integer>> byHours = new LinkedHashMap<>();
        shifts.stream()
                .sorted(java.util.Comparator.comparing(Shift::dayOfWeek).thenComparing(Shift::startTime))
                .forEach(shift -> byHours.computeIfAbsent(hours(shift), k -> new java.util.ArrayList<>())
                        .add(shift.dayOfWeek()));
        return byHours.entrySet().stream()
                .map(entry -> entry.getValue().stream()
                        .map(day -> DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                        .collect(Collectors.joining(", ")) + " " + entry.getKey())
                .collect(Collectors.joining("; "));
    }

    private static String hours(Shift shift) {
        String breaks = shift.breaksOrEmpty().isEmpty() ? ""
                : " (break " + shift.breaksOrEmpty().stream()
                        .map(b -> b.startTime() + "-" + b.endTime())
                        .collect(Collectors.joining(", ")) + ")";
        return shift.startTime() + "-" + shift.endTime() + breaks;
    }

    private String poolName(UUID id) {
        return snapshot.pools().stream().filter(p -> p.id().equals(id))
                .map(Snapshot.PoolSnapshot::name).findFirst().orElse("Unknown resource");
    }

    private String when(Instant instant) {
        return WHEN.format(instant.atZone(zone));
    }

    private static String units(int count) {
        return count + (count == 1 ? " unit" : " units");
    }

    private static String signed(BigDecimal value) {
        String text = plain(value);
        return value.signum() > 0 ? "+" + text : text;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
