package com.realitycheck.factory.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * A factory's weekly working pattern as plain values, for modules that must not see entities.
 * Shifts are ordered by day then start; breaks by start.
 */
public record FactoryCalendar(ZoneId zone, List<Shift> shifts) {

    public FactoryCalendar {
        shifts = List.copyOf(shifts);
    }

    public record Shift(DayOfWeek day, LocalTime start, LocalTime end, List<Break> breaks) {

        public Shift {
            breaks = List.copyOf(breaks);
        }

        public long workingSeconds() {
            long seconds = Duration.between(start, end).toSeconds();
            for (Break pause : breaks) {
                seconds -= Duration.between(pause.start(), pause.end()).toSeconds();
            }
            return seconds;
        }
    }

    public record Break(LocalTime start, LocalTime end) {
    }

    public long weeklyWorkingSeconds() {
        return shifts.stream().mapToLong(Shift::workingSeconds).sum();
    }
}
