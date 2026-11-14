package com.realitycheck.simulation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.simulation.engine.IntervalSet.Interval;
import com.realitycheck.simulation.engine.SimulationInput.BreakSpec;
import com.realitycheck.simulation.engine.SimulationInput.CalendarSpec;
import com.realitycheck.simulation.engine.SimulationInput.ShiftSpec;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class WorkingCalendarTest {

    private static final long HOUR = 3_600;
    private static final long FAR = Long.MAX_VALUE / 4;

    private final WorkingCalendar demo = new WorkingCalendar(demoSpec());

    @Test
    void localShiftsAreConvertedToUtcInTheCalendarsZone() {
        // 09:00 in Asia/Kolkata is 03:30 UTC.
        long monday9 = kolkata(2026, 1, 5, 9, 0);

        assertThat(monday9).isEqualTo(java.time.Instant.parse("2026-01-05T03:30:00Z").getEpochSecond());
        assertThat(demo.isWorking(monday9)).isTrue();
        assertThat(demo.isWorking(monday9 - 1)).isFalse();
    }

    @Test
    void intervalsAreHalfOpenAndBreaksAreNotWorkingTime() {
        assertThat(demo.isWorking(kolkata(2026, 1, 5, 12, 59))).isTrue();
        assertThat(demo.isWorking(kolkata(2026, 1, 5, 13, 0))).isFalse();
        assertThat(demo.isWorking(kolkata(2026, 1, 5, 13, 59))).isFalse();
        assertThat(demo.isWorking(kolkata(2026, 1, 5, 14, 0))).isTrue();
        assertThat(demo.isWorking(kolkata(2026, 1, 5, 18, 0))).isFalse();
        assertThat(demo.isWorking(kolkata(2026, 1, 11, 11, 0))).as("Sunday").isFalse();
    }

    @Test
    void addingWorkWalksAcrossBreaksNightsAndClosedDays() {
        long monday9 = kolkata(2026, 1, 5, 9, 0);

        assertThat(demo.addWorkingSeconds(monday9, 4 * HOUR, FAR)).isEqualTo(kolkata(2026, 1, 5, 13, 0));
        assertThat(demo.addWorkingSeconds(monday9, 4 * HOUR + 1, FAR)).isEqualTo(kolkata(2026, 1, 5, 14, 0) + 1);
        assertThat(demo.addWorkingSeconds(monday9, 8 * HOUR, FAR)).isEqualTo(kolkata(2026, 1, 5, 18, 0));
        assertThat(demo.addWorkingSeconds(monday9, 9 * HOUR, FAR)).isEqualTo(kolkata(2026, 1, 6, 10, 0));
        // 48 working hours is the whole week, ending Saturday 18:00; one more hour lands on Monday.
        assertThat(demo.addWorkingSeconds(monday9, 48 * HOUR, FAR)).isEqualTo(kolkata(2026, 1, 10, 18, 0));
        assertThat(demo.addWorkingSeconds(monday9, 49 * HOUR, FAR)).isEqualTo(kolkata(2026, 1, 12, 10, 0));
    }

    @Test
    void addingNoWorkReturnsTheStartEvenWhenClosed() {
        long sunday = kolkata(2026, 1, 11, 11, 0);

        assertThat(demo.addWorkingSeconds(sunday, 0, FAR)).isEqualTo(sunday);
    }

    @Test
    void workThatCannotFinishByTheLimitNeverCompletes() {
        long monday9 = kolkata(2026, 1, 5, 9, 0);

        assertThat(demo.addWorkingSeconds(monday9, 100 * HOUR, monday9 + 24 * HOUR)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void countsWorkingSecondsInARange() {
        long monday9 = kolkata(2026, 1, 5, 9, 0);

        assertThat(demo.workingSecondsBetween(monday9, kolkata(2026, 1, 5, 18, 0))).isEqualTo(8 * HOUR);
        assertThat(demo.workingSecondsBetween(kolkata(2026, 1, 5, 12, 30), kolkata(2026, 1, 5, 14, 30)))
                .isEqualTo(HOUR);
        assertThat(demo.workingSecondsBetween(kolkata(2026, 1, 5, 0, 0), kolkata(2026, 1, 12, 0, 0)))
                .isEqualTo(48 * HOUR);
        assertThat(demo.workingSecondsBetween(monday9, monday9)).isZero();
        assertThat(demo.workingSecondsBetween(monday9 + 10, monday9)).isZero();
    }

    @Test
    void findsTheNextIntervalStartStrictlyAfterAnInstant() {
        assertThat(demo.nextIntervalStartAfter(kolkata(2026, 1, 5, 9, 0), FAR))
                .isEqualTo(kolkata(2026, 1, 5, 14, 0));
        assertThat(demo.nextIntervalStartAfter(kolkata(2026, 1, 5, 15, 0), FAR))
                .isEqualTo(kolkata(2026, 1, 6, 9, 0));
        assertThat(demo.nextIntervalStartAfter(kolkata(2026, 1, 10, 17, 0), FAR))
                .as("Saturday afternoon to Monday morning").isEqualTo(kolkata(2026, 1, 12, 9, 0));
        assertThat(demo.nextIntervalStartAfter(kolkata(2026, 1, 5, 15, 0), kolkata(2026, 1, 5, 20, 0)))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void aShiftAcrossASpringForwardGapHasOneRealHourLess() {
        // Europe/Berlin jumps from 02:00 to 03:00 on Sunday 29 March 2026.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        WorkingCalendar calendar = new WorkingCalendar(new CalendarSpec("Europe/Berlin",
                List.of(new ShiftSpec(7, HOURS(1), HOURS(5), List.of()))));
        long start = LocalDateTime.of(2026, 3, 29, 0, 0).atZone(berlin).toEpochSecond();
        long end = LocalDateTime.of(2026, 3, 30, 0, 0).atZone(berlin).toEpochSecond();

        assertThat(calendar.workingSecondsBetween(start, end)).isEqualTo(3 * HOUR);
        assertThat(end - start).as("that day has 23 hours").isEqualTo(23 * HOUR);
    }

    @Test
    void aShiftAcrossAnAutumnOverlapHasOneRealHourMore() {
        // Europe/Berlin repeats 02:00 to 03:00 on Sunday 25 October 2026.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        WorkingCalendar calendar = new WorkingCalendar(new CalendarSpec("Europe/Berlin",
                List.of(new ShiftSpec(7, HOURS(1), HOURS(5), List.of()))));
        long start = LocalDateTime.of(2026, 10, 25, 0, 0).atZone(berlin).toEpochSecond();
        long end = LocalDateTime.of(2026, 10, 26, 0, 0).atZone(berlin).toEpochSecond();

        assertThat(calendar.workingSecondsBetween(start, end)).isEqualTo(5 * HOUR);
    }

    @Test
    void aShiftEndingAtTwentyFourHundredRunsToMidnightAndJoinsTheNextDay() {
        WorkingCalendar calendar = new WorkingCalendar(new CalendarSpec("UTC", List.of(
                new ShiftSpec(1, HOURS(22), 86_400, List.of()),
                new ShiftSpec(2, 0, HOURS(2), List.of()))));
        long monday22 = LocalDateTime.of(2026, 1, 5, 22, 0).atZone(ZoneId.of("UTC")).toEpochSecond();

        assertThat(calendar.workingSecondsBetween(monday22, monday22 + 10 * HOUR)).isEqualTo(4 * HOUR);
        assertThat(calendar.addWorkingSeconds(monday22, 3 * HOUR, FAR)).isEqualTo(monday22 + 3 * HOUR);
        assertThat(calendar.isWorking(monday22 + 2 * HOUR)).as("midnight").isTrue();
    }

    @Test
    void overlappingShiftsOnOneDayAreCountedOnce() {
        WorkingCalendar calendar = new WorkingCalendar(new CalendarSpec("UTC", List.of(
                new ShiftSpec(1, HOURS(9), HOURS(14), List.of()),
                new ShiftSpec(1, HOURS(12), HOURS(18), List.of()))));
        long monday = LocalDateTime.of(2026, 1, 5, 0, 0).atZone(ZoneId.of("UTC")).toEpochSecond();

        assertThat(calendar.workingSecondsBetween(monday, monday + 24 * HOUR)).isEqualTo(9 * HOUR);
    }

    @Test
    void periodsCanBeExcludedOnTopOfTheCalendar() {
        long monday9 = kolkata(2026, 1, 5, 9, 0);
        WorkingCalendar withDowntime = demo.without(IntervalSet.of(monday9 + HOUR, monday9 + 3 * HOUR));

        assertThat(withDowntime.isWorking(monday9 + 2 * HOUR)).isFalse();
        assertThat(withDowntime.workingSecondsBetween(monday9, kolkata(2026, 1, 5, 18, 0))).isEqualTo(6 * HOUR);
        assertThat(withDowntime.addWorkingSeconds(monday9, 2 * HOUR, FAR)).isEqualTo(monday9 + 4 * HOUR);
        assertThat(demo.isWorking(monday9 + 2 * HOUR)).as("the original calendar is unchanged").isTrue();
    }

    @Test
    void aCalendarWithNoShiftsHasNoWorkingTime() {
        WorkingCalendar closed = new WorkingCalendar(new CalendarSpec("UTC", List.of()));

        assertThat(closed.hasWorkingTime()).isFalse();
        assertThat(closed.addWorkingSeconds(0, 1, 10 * 86_400)).isEqualTo(Long.MAX_VALUE);
        assertThat(closed.workingSecondsBetween(0, 10 * 86_400)).isZero();
        assertThat(demo.hasWorkingTime()).isTrue();
    }

    @Property
    void addingWorkThenMeasuringItGivesTheSameAmountBack(
            @ForAll @IntRange(min = 0, max = 14 * 86_400) int startOffset,
            @ForAll @IntRange(min = 1, max = 200_000) int seconds) {
        long start = kolkata(2026, 1, 5, 0, 0) + startOffset;

        long end = demo.addWorkingSeconds(start, seconds, FAR);

        assertThat(demo.workingSecondsBetween(start, end)).isEqualTo(seconds);
        assertThat(demo.workingSecondsBetween(start, end - 1)).isEqualTo(seconds - 1L);
    }

    @Property
    void workingTimeOverARangeIsTheSumOverItsParts(
            @ForAll @IntRange(min = 0, max = 14 * 86_400) int a,
            @ForAll @IntRange(min = 0, max = 14 * 86_400) int b,
            @ForAll @IntRange(min = 0, max = 14 * 86_400) int c) {
        long base = kolkata(2026, 1, 5, 0, 0);
        long[] points = java.util.stream.LongStream.of(a, b, c).sorted().map(offset -> base + offset).toArray();

        assertThat(demo.workingSecondsBetween(points[0], points[2]))
                .isEqualTo(demo.workingSecondsBetween(points[0], points[1])
                        + demo.workingSecondsBetween(points[1], points[2]));
    }

    @Property
    void intervalSetsBehaveLikeSetsOfSeconds(
            @ForAll @Size(max = 6) List<@IntRange(min = 0, max = 60) Integer> aPoints,
            @ForAll @Size(max = 6) List<@IntRange(min = 0, max = 60) Integer> bPoints) {
        IntervalSet a = fromPoints(aPoints);
        IntervalSet b = fromPoints(bPoints);

        IntervalSet union = a.union(b);
        IntervalSet difference = a.subtract(b);

        for (long second = 0; second <= 60; second++) {
            assertThat(union.contains(second)).isEqualTo(a.contains(second) || b.contains(second));
            assertThat(difference.contains(second)).isEqualTo(a.contains(second) && !b.contains(second));
        }
        assertThat(difference.totalLength() + a.subtract(difference).totalLength()).isEqualTo(a.totalLength());
        assertThat(union.intervals()).isSortedAccordingTo(java.util.Comparator.comparingLong(Interval::start));
        for (int i = 1; i < union.intervals().size(); i++) {
            assertThat(union.intervals().get(i).start()).isGreaterThan(union.intervals().get(i - 1).end());
        }
    }

    /** Consecutive pairs of points become intervals, in whatever order the points came. */
    private static IntervalSet fromPoints(List<Integer> points) {
        List<Interval> intervals = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i += 2) {
            intervals.add(new Interval(Math.min(points.get(i), points.get(i + 1)),
                    Math.max(points.get(i), points.get(i + 1))));
        }
        return IntervalSet.of(intervals);
    }

    private static CalendarSpec demoSpec() {
        List<ShiftSpec> shifts = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            shifts.add(new ShiftSpec(day, HOURS(9), HOURS(18), List.of(new BreakSpec(HOURS(13), HOURS(14)))));
        }
        return new CalendarSpec("Asia/Kolkata", shifts);
    }

    @SuppressWarnings("checkstyle:MethodName")
    private static int HOURS(int hours) {
        return hours * 3_600;
    }

    private static long kolkata(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();
    }
}
