package com.realitycheck.factory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.common.error.FieldViolation;
import com.realitycheck.factory.api.FactoryDtos.BreakRequest;
import com.realitycheck.factory.api.FactoryDtos.CalendarRequest;
import com.realitycheck.factory.api.FactoryDtos.ShiftRequest;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarValidatorTest {

    @Test
    void aNormalWeekIsValid() {
        CalendarRequest calendar = new CalendarRequest(List.of(
                shift(1, "09:00", "18:00", pause("13:00", "14:00")),
                shift(2, "09:00", "13:00"),
                shift(2, "14:00", "18:00")));

        assertThat(CalendarValidator.validate(calendar)).isEmpty();
    }

    @Test
    void shiftsThatTouchButDoNotOverlapAreValid() {
        CalendarRequest calendar = new CalendarRequest(List.of(
                shift(1, "06:00", "14:00"),
                shift(1, "14:00", "22:00")));

        assertThat(CalendarValidator.validate(calendar)).isEmpty();
    }

    @Test
    void aShiftMustEndAfterItStarts() {
        assertThat(CalendarValidator.validate(new CalendarRequest(List.of(shift(1, "18:00", "09:00")))))
                .containsExactly(new FieldViolation("calendar.shifts[0].endTime", "must be after startTime"));
    }

    @Test
    void overlappingShiftsOnTheSameDayAreRejected() {
        CalendarRequest calendar = new CalendarRequest(List.of(
                shift(3, "09:00", "18:00"),
                shift(3, "17:00", "22:00"),
                shift(4, "17:00", "22:00")));

        assertThat(CalendarValidator.validate(calendar))
                .containsExactly(new FieldViolation("calendar.shifts[1]", "overlaps another shift on the same day"));
    }

    @Test
    void aBreakMustSitInsideItsShift() {
        CalendarRequest calendar = new CalendarRequest(List.of(
                shift(1, "09:00", "18:00", pause("08:30", "09:30")),
                shift(2, "09:00", "18:00", pause("17:30", "18:30")),
                shift(3, "09:00", "18:00", pause("14:00", "13:00"))));

        assertThat(CalendarValidator.validate(calendar)).containsExactly(
                new FieldViolation("calendar.shifts[0].breaks[0]", "must be inside the shift"),
                new FieldViolation("calendar.shifts[1].breaks[0]", "must be inside the shift"),
                new FieldViolation("calendar.shifts[2].breaks[0].endTime", "must be after startTime"));
    }

    @Test
    void breaksInOneShiftMustNotOverlap() {
        CalendarRequest calendar = new CalendarRequest(List.of(
                shift(1, "09:00", "18:00", pause("13:00", "14:00"), pause("13:30", "14:30"))));

        assertThat(CalendarValidator.validate(calendar))
                .containsExactly(new FieldViolation("calendar.shifts[0].breaks", "must not overlap each other"));
    }

    @Test
    void weeklyWorkingTimeIsShiftTimeMinusBreaks() {
        FactoryCalendar.Shift day = new FactoryCalendar.Shift(DayOfWeek.MONDAY, LocalTime.of(9, 0),
                LocalTime.of(18, 0), List.of(new FactoryCalendar.Break(LocalTime.of(13, 0), LocalTime.of(14, 0))));
        FactoryCalendar calendar = new FactoryCalendar(ZoneId.of("Asia/Kolkata"), List.of(day, day, day));

        assertThat(day.workingSeconds()).isEqualTo(8 * 3600);
        assertThat(calendar.weeklyWorkingSeconds()).isEqualTo(3 * 8 * 3600);
    }

    private static ShiftRequest shift(int day, String start, String end, BreakRequest... breaks) {
        return new ShiftRequest(day, LocalTime.parse(start), LocalTime.parse(end), List.of(breaks));
    }

    private static BreakRequest pause(String start, String end) {
        return new BreakRequest(LocalTime.parse(start), LocalTime.parse(end));
    }
}
