package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.IntervalSet.Interval;
import com.realitycheck.simulation.engine.SimulationInput.BreakSpec;
import com.realitycheck.simulation.engine.SimulationInput.CalendarSpec;
import com.realitycheck.simulation.engine.SimulationInput.ShiftSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Working time: the factory calendar, optionally minus periods in which work cannot proceed for
 * another reason (a required pool at zero capacity). Weekly shifts are local to the calendar's
 * zone and are expanded one local day at a time into UTC intervals with breaks removed.
 *
 * <p>The zone is used here and nowhere else in the engine; everything outside works in epoch
 * seconds. Daylight-saving gaps and overlaps are resolved by {@link ZonedDateTime#of}, which is
 * deterministic.
 */
final class WorkingCalendar {

    private static final int SECONDS_PER_DAY = 86_400;

    private final ZoneId zone;
    private final List<ShiftSpec> shifts;
    private final IntervalSet excluded;
    private final TreeMap<Long, List<Interval>> intervalsByEpochDay = new TreeMap<>();

    WorkingCalendar(CalendarSpec spec) {
        this(ZoneId.of(spec.zoneId()), spec.shifts(), IntervalSet.EMPTY);
    }

    private WorkingCalendar(ZoneId zone, List<ShiftSpec> shifts, IntervalSet excluded) {
        this.zone = zone;
        this.shifts = shifts;
        this.excluded = excluded;
    }

    /** The same calendar with the given periods also treated as non-working. */
    WorkingCalendar without(IntervalSet nonWorking) {
        return nonWorking.isEmpty() ? this : new WorkingCalendar(zone, shifts, excluded.union(nonWorking));
    }

    boolean isWorking(long instant) {
        for (Interval interval : intervalsOn(dateOf(instant))) {
            if (interval.start() <= instant && instant < interval.end()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param limit give up beyond this instant
     * @return the instant at which {@code seconds} of working time have passed since
     *         {@code start}; {@code start} itself when {@code seconds} is zero;
     *         {@code Long.MAX_VALUE} if that does not happen by {@code limit}
     */
    long addWorkingSeconds(long start, long seconds, long limit) {
        if (seconds == 0) {
            return start;
        }
        long remaining = seconds;
        LocalDate day = dateOf(start);
        while (startOf(day) <= limit) {
            for (Interval interval : intervalsOn(day)) {
                if (interval.end() <= start) {
                    continue;
                }
                long from = Math.max(interval.start(), start);
                long available = interval.end() - from;
                if (remaining <= available) {
                    return from + remaining;
                }
                remaining -= available;
            }
            day = day.plusDays(1);
        }
        return Long.MAX_VALUE;
    }

    /** Working seconds in {@code [from, to)}; zero when the range is empty. */
    long workingSecondsBetween(long from, long to) {
        if (to <= from) {
            return 0;
        }
        long total = 0;
        LocalDate lastDay = dateOf(to);
        for (LocalDate day = dateOf(from); !day.isAfter(lastDay); day = day.plusDays(1)) {
            for (Interval interval : intervalsOn(day)) {
                long start = Math.max(interval.start(), from);
                long end = Math.min(interval.end(), to);
                if (end > start) {
                    total += end - start;
                }
            }
        }
        return total;
    }

    /**
     * @return the start of the first working interval that begins strictly after {@code instant},
     *         or {@code Long.MAX_VALUE} if none begins by {@code limit}
     */
    long nextIntervalStartAfter(long instant, long limit) {
        LocalDate day = dateOf(instant);
        while (startOf(day) <= limit) {
            for (Interval interval : intervalsOn(day)) {
                if (interval.start() > instant) {
                    return interval.start() <= limit ? interval.start() : Long.MAX_VALUE;
                }
            }
            day = day.plusDays(1);
        }
        return Long.MAX_VALUE;
    }

    /** Whether the weekly pattern has any working time at all. */
    boolean hasWorkingTime() {
        return shifts.stream().anyMatch(shift -> shift.endSecondOfDay() > shift.startSecondOfDay());
    }

    private LocalDate dateOf(long instant) {
        return Instant.ofEpochSecond(instant).atZone(zone).toLocalDate();
    }

    private long startOf(LocalDate day) {
        return day.atStartOfDay(zone).toEpochSecond();
    }

    private List<Interval> intervalsOn(LocalDate day) {
        return intervalsByEpochDay.computeIfAbsent(day.toEpochDay(), key -> expand(day));
    }

    private List<Interval> expand(LocalDate day) {
        List<Interval> working = new ArrayList<>();
        List<Interval> breaks = new ArrayList<>();
        for (ShiftSpec shift : shifts) {
            if (shift.dayOfWeek() != day.getDayOfWeek().getValue()) {
                continue;
            }
            working.add(new Interval(at(day, shift.startSecondOfDay()), at(day, shift.endSecondOfDay())));
            for (BreakSpec pause : shift.breaks()) {
                breaks.add(new Interval(at(day, pause.startSecondOfDay()), at(day, pause.endSecondOfDay())));
            }
        }
        return IntervalSet.of(working).subtract(IntervalSet.of(breaks)).subtract(excluded).intervals();
    }

    private long at(LocalDate day, int secondOfDay) {
        if (secondOfDay >= SECONDS_PER_DAY) {
            return startOf(day.plusDays(1));
        }
        return ZonedDateTime.of(day, LocalTime.ofSecondOfDay(secondOfDay), zone).toEpochSecond();
    }
}
