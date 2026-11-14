package com.realitycheck.factory.service;

import com.realitycheck.common.error.FieldViolation;
import com.realitycheck.factory.api.FactoryDtos.BreakRequest;
import com.realitycheck.factory.api.FactoryDtos.CalendarRequest;
import com.realitycheck.factory.api.FactoryDtos.ShiftRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The calendar rules that involve more than one field: a period must end after it starts, shifts
 * on the same day must not overlap, and breaks must sit inside their shift without overlapping.
 * Pure, so it can be tested without a database.
 */
public final class CalendarValidator {

    private CalendarValidator() {
    }

    public static List<FieldViolation> validate(CalendarRequest calendar) {
        List<FieldViolation> errors = new ArrayList<>();
        List<ShiftRequest> shifts = calendar.shifts();

        for (int i = 0; i < shifts.size(); i++) {
            ShiftRequest shift = shifts.get(i);
            String path = "calendar.shifts[" + i + "]";
            if (!shift.endTime().isAfter(shift.startTime())) {
                errors.add(new FieldViolation(path + ".endTime", "must be after startTime"));
                continue;
            }
            validateBreaks(shift, path, errors);
        }

        for (int i = 0; i < shifts.size(); i++) {
            for (int j = i + 1; j < shifts.size(); j++) {
                ShiftRequest a = shifts.get(i);
                ShiftRequest b = shifts.get(j);
                boolean sameDay = a.dayOfWeek().equals(b.dayOfWeek());
                if (sameDay && a.startTime().isBefore(b.endTime()) && b.startTime().isBefore(a.endTime())) {
                    errors.add(new FieldViolation("calendar.shifts[" + j + "]",
                            "overlaps another shift on the same day"));
                }
            }
        }
        return errors;
    }

    private static void validateBreaks(ShiftRequest shift, String shiftPath, List<FieldViolation> errors) {
        List<BreakRequest> breaks = shift.breaksOrEmpty();
        for (int i = 0; i < breaks.size(); i++) {
            BreakRequest pause = breaks.get(i);
            String path = shiftPath + ".breaks[" + i + "]";
            if (!pause.endTime().isAfter(pause.startTime())) {
                errors.add(new FieldViolation(path + ".endTime", "must be after startTime"));
            } else if (pause.startTime().isBefore(shift.startTime()) || pause.endTime().isAfter(shift.endTime())) {
                errors.add(new FieldViolation(path, "must be inside the shift"));
            }
        }
        List<BreakRequest> sorted = breaks.stream()
                .filter(b -> b.endTime().isAfter(b.startTime()))
                .sorted(Comparator.comparing(BreakRequest::startTime))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startTime().isBefore(sorted.get(i - 1).endTime())) {
                errors.add(new FieldViolation(shiftPath + ".breaks", "must not overlap each other"));
                break;
            }
        }
    }
}
