package com.realitycheck.factory.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Arithmetic in working time: the time a factory is open according to its calendar. Shifts are
 * local to the factory's zone and are turned into half-open UTC intervals {@code [start, end)}
 * with breaks removed. Pure and deterministic.
 */
public final class WorkingTime {

    /** Longest stretch searched for the next shift before concluding the calendar never opens. */
    private static final int MAX_CLOSED_DAYS = 14;

    private final FactoryCalendar calendar;

    public WorkingTime(FactoryCalendar calendar) {
        if (calendar.shifts().isEmpty()) {
            throw new IllegalArgumentException("The calendar has no shifts");
        }
        this.calendar = calendar;
    }

    private record Interval(Instant start, Instant end) {
    }

    /** @return {@code instant} if the factory is open then, otherwise the start of the next working interval */
    public Instant nextWorkingInstant(Instant instant) {
        return addWorkingSeconds(instant, 0);
    }

    /**
     * @return the instant by which {@code seconds} of working time have passed since {@code start}.
     *         With zero seconds this is the first working instant at or after {@code start}. Work
     *         that exactly fills an interval ends at that interval's end, not at the next start.
     */
    public Instant addWorkingSeconds(Instant start, long seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("seconds must not be negative");
        }
        long remaining = seconds;
        LocalDate day = start.atZone(calendar.zone()).toLocalDate().minusDays(1);
        int closedDays = 0;
        while (closedDays <= MAX_CLOSED_DAYS) {
            List<Interval> intervals = intervalsOn(day);
            boolean usable = false;
            for (Interval interval : intervals) {
                if (!interval.end().isAfter(start)) {
                    continue;
                }
                usable = true;
                Instant from = interval.start().isAfter(start) ? interval.start() : start;
                long available = interval.end().getEpochSecond() - from.getEpochSecond();
                if (remaining == 0) {
                    return from;
                }
                if (remaining <= available) {
                    return from.plusSeconds(remaining);
                }
                remaining -= available;
            }
            closedDays = usable ? 0 : closedDays + 1;
            day = day.plusDays(1);
        }
        throw new IllegalStateException("No working time found in the calendar");
    }

    /** @return working seconds in {@code [from, to)}; zero when the range is empty */
    public long workingSecondsBetween(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return 0;
        }
        long total = 0;
        LocalDate day = from.atZone(calendar.zone()).toLocalDate().minusDays(1);
        LocalDate lastDay = to.atZone(calendar.zone()).toLocalDate();
        while (!day.isAfter(lastDay)) {
            for (Interval interval : intervalsOn(day)) {
                Instant start = interval.start().isAfter(from) ? interval.start() : from;
                Instant end = interval.end().isBefore(to) ? interval.end() : to;
                if (end.isAfter(start)) {
                    total += end.getEpochSecond() - start.getEpochSecond();
                }
            }
            day = day.plusDays(1);
        }
        return total;
    }

    /** Working intervals of the shifts that start on a local date, in time order, breaks removed. */
    private List<Interval> intervalsOn(LocalDate day) {
        List<Interval> intervals = new ArrayList<>();
        for (FactoryCalendar.Shift shift : calendar.shifts()) {
            if (shift.day() != day.getDayOfWeek()) {
                continue;
            }
            Instant cursor = at(day, shift.start());
            for (FactoryCalendar.Break pause : shift.breaks()) {
                Instant pauseStart = at(day, pause.start());
                if (pauseStart.isAfter(cursor)) {
                    intervals.add(new Interval(cursor, pauseStart));
                }
                cursor = at(day, pause.end());
            }
            Instant shiftEnd = at(day, shift.end());
            if (shiftEnd.isAfter(cursor)) {
                intervals.add(new Interval(cursor, shiftEnd));
            }
        }
        intervals.sort(Comparator.comparing(Interval::start));
        return intervals;
    }

    private Instant at(LocalDate day, java.time.LocalTime time) {
        return ZonedDateTime.of(day, time, calendar.zone()).toInstant();
    }
}
