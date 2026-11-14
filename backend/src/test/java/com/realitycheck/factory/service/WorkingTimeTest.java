package com.realitycheck.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The demonstration calendar: Monday to Saturday 09:00 to 18:00, break 13:00 to 14:00, in Asia/Kolkata. */
class WorkingTimeTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final long HOUR = 3_600;

    private final WorkingTime workingTime = new WorkingTime(demoCalendar());

    @Test
    void workInsideOneIntervalEndsThatManySecondsLater() {
        assertThat(workingTime.addWorkingSeconds(monday(9, 0), 2 * HOUR)).isEqualTo(monday(11, 0));
    }

    @Test
    void workPausesForTheBreak() {
        assertThat(workingTime.addWorkingSeconds(monday(12, 0), 2 * HOUR)).isEqualTo(monday(15, 0));
    }

    @Test
    void workCarriesOverToTheNextMorning() {
        assertThat(workingTime.addWorkingSeconds(monday(17, 0), 3 * HOUR)).isEqualTo(tuesday(11, 0));
    }

    @Test
    void workSkipsTheClosedSunday() {
        Instant saturdayAfternoon = at(2026, 1, 10, 17, 0);

        assertThat(workingTime.addWorkingSeconds(saturdayAfternoon, 2 * HOUR)).isEqualTo(at(2026, 1, 12, 10, 0));
    }

    @Test
    void workThatExactlyFillsTheDayEndsAtClosingTimeNotTheNextMorning() {
        assertThat(workingTime.addWorkingSeconds(monday(9, 0), 8 * HOUR)).isEqualTo(monday(18, 0));
        assertThat(workingTime.addWorkingSeconds(monday(9, 0), 4 * HOUR)).isEqualTo(monday(13, 0));
    }

    @Test
    void workStartedOutsideHoursBeginsAtTheNextOpening() {
        assertThat(workingTime.addWorkingSeconds(monday(6, 0), HOUR)).isEqualTo(monday(10, 0));
        assertThat(workingTime.addWorkingSeconds(monday(13, 30), HOUR)).isEqualTo(monday(15, 0));
        assertThat(workingTime.addWorkingSeconds(monday(22, 0), HOUR)).isEqualTo(tuesday(10, 0));
    }

    @Test
    void theNextWorkingInstantIsNowWhenOpenAndTheNextOpeningOtherwise() {
        assertThat(workingTime.nextWorkingInstant(monday(10, 30))).isEqualTo(monday(10, 30));
        assertThat(workingTime.nextWorkingInstant(monday(13, 0))).isEqualTo(monday(14, 0));
        assertThat(workingTime.nextWorkingInstant(monday(18, 0))).isEqualTo(tuesday(9, 0));
        assertThat(workingTime.nextWorkingInstant(at(2026, 1, 11, 12, 0))).isEqualTo(at(2026, 1, 12, 9, 0));
    }

    @Test
    void countsWorkingSecondsBetweenTwoInstants() {
        assertThat(workingTime.workingSecondsBetween(monday(9, 0), monday(18, 0))).isEqualTo(8 * HOUR);
        assertThat(workingTime.workingSecondsBetween(monday(12, 30), monday(14, 30))).isEqualTo(HOUR);
        assertThat(workingTime.workingSecondsBetween(monday(17, 0), tuesday(10, 0))).isEqualTo(2 * HOUR);
        assertThat(workingTime.workingSecondsBetween(monday(0, 0), at(2026, 1, 12, 0, 0))).isEqualTo(48 * HOUR);
        assertThat(workingTime.workingSecondsBetween(monday(10, 0), monday(10, 0))).isZero();
        assertThat(workingTime.workingSecondsBetween(monday(11, 0), monday(10, 0))).isZero();
    }

    @Test
    void addingThenMeasuringGivesBackTheSameWork() {
        for (long seconds : new long[] {1, 59, 3_600, 28_800, 100_000, 345_601}) {
            Instant start = monday(11, 17);
            Instant end = workingTime.addWorkingSeconds(start, seconds);

            assertThat(workingTime.workingSecondsBetween(start, end)).isEqualTo(seconds);
        }
    }

    @Test
    void rejectsNegativeWorkAndEmptyCalendars() {
        assertThatIllegalArgumentException().isThrownBy(() -> workingTime.addWorkingSeconds(monday(9, 0), -1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkingTime(new FactoryCalendar(ZONE, List.of())));
    }

    private static FactoryCalendar demoCalendar() {
        List<FactoryCalendar.Shift> shifts = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day != DayOfWeek.SUNDAY) {
                shifts.add(new FactoryCalendar.Shift(day, LocalTime.of(9, 0), LocalTime.of(18, 0),
                        List.of(new FactoryCalendar.Break(LocalTime.of(13, 0), LocalTime.of(14, 0)))));
            }
        }
        return new FactoryCalendar(ZONE, shifts);
    }

    /** Monday 5 January 2026, factory local time. */
    private static Instant monday(int hour, int minute) {
        return at(2026, 1, 5, hour, minute);
    }

    private static Instant tuesday(int hour, int minute) {
        return at(2026, 1, 6, hour, minute);
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant();
    }
}
