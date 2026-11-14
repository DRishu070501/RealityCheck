package com.realitycheck.factory.service;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.domain.Ids;
import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.FieldViolation;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.factory.api.FactoryDtos.BreakRequest;
import com.realitycheck.factory.api.FactoryDtos.BreakResponse;
import com.realitycheck.factory.api.FactoryDtos.CalendarResponse;
import com.realitycheck.factory.api.FactoryDtos.CreateFactoryRequest;
import com.realitycheck.factory.api.FactoryDtos.FactoryResponse;
import com.realitycheck.factory.api.FactoryDtos.FactorySummaryResponse;
import com.realitycheck.factory.api.FactoryDtos.ShiftRequest;
import com.realitycheck.factory.api.FactoryDtos.ShiftResponse;
import com.realitycheck.factory.domain.Factory;
import com.realitycheck.factory.domain.WorkingShift;
import com.realitycheck.factory.persistence.FactoryRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FactoryService {

    private final FactoryRepository factories;
    private final List<FactoryCountProvider> countProviders;
    private final Clock clock;

    public FactoryService(FactoryRepository factories, List<FactoryCountProvider> countProviders, Clock clock) {
        this.factories = factories;
        this.countProviders = countProviders;
        this.clock = clock;
    }

    @Transactional
    public FactoryResponse create(CreateFactoryRequest request) {
        return create(request, Ids.random(), clock.instant(), false);
    }

    /** Creation with a caller-chosen id and timestamp; the demonstration seed uses fixed values. */
    @Transactional
    public FactoryResponse create(CreateFactoryRequest request, UUID id, Instant now, boolean demo) {
        List<FieldViolation> errors = new ArrayList<>();
        if (!isZone(request.timeZone())) {
            errors.add(new FieldViolation("timeZone", "is not a known time zone, for example Asia/Kolkata"));
        }
        if (!isCurrency(request.currencyCode())) {
            errors.add(new FieldViolation("currencyCode", "is not a known ISO 4217 currency"));
        }
        errors.addAll(CalendarValidator.validate(request.calendar()));
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        String name = request.name().strip();
        if (factories.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("A factory named \"" + name + "\" already exists");
        }

        Factory factory = new Factory(id, now, name, blankToNull(request.description()), request.timeZone(),
                request.currencyCode(), demo);
        for (ShiftRequest shiftRequest : request.calendar().shifts()) {
            WorkingShift shift = factory.addShift(DayOfWeek.of(shiftRequest.dayOfWeek()),
                    shiftRequest.startTime(), shiftRequest.endTime());
            for (BreakRequest pause : shiftRequest.breaksOrEmpty()) {
                shift.addBreak(pause.startTime(), pause.endTime());
            }
        }
        return toResponse(factories.saveAndFlush(factory));
    }

    @Transactional(readOnly = true)
    public FactoryResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<FactorySummaryResponse> list(Pageable pageable) {
        return PageResponse.from(factories.findAll(pageable), f -> new FactorySummaryResponse(
                f.getId(), f.getName(), f.getTimeZone(), f.getCurrencyCode(), f.isDemo()));
    }

    @Transactional(readOnly = true)
    public FactoryCalendar calendar(UUID factoryId) {
        return toCalendar(find(factoryId));
    }

    @Transactional(readOnly = true)
    public long count() {
        return factories.count();
    }

    private Factory find(UUID id) {
        return factories.findById(id).orElseThrow(() -> ApiException.notFound("Factory", id));
    }

    private FactoryResponse toResponse(Factory factory) {
        List<ShiftResponse> shifts = sortedShifts(factory).stream()
                .map(shift -> new ShiftResponse(shift.getId(), shift.getDay().getValue(), shift.getStartTime(),
                        shift.getEndTime(),
                        shift.getBreaks().stream()
                                .map(b -> new BreakResponse(b.getStartTime(), b.getEndTime()))
                                .toList()))
                .toList();
        Map<String, Long> counts = new TreeMap<>();
        for (FactoryCountProvider provider : countProviders) {
            counts.put(provider.key(), provider.count(factory.getId()));
        }
        return new FactoryResponse(factory.getId(), factory.getName(), factory.getDescription(),
                factory.getTimeZone(), factory.getCurrencyCode(), factory.isDemo(),
                new CalendarResponse(shifts, toCalendar(factory).weeklyWorkingSeconds()), counts,
                factory.getVersion(), factory.getCreatedAt(), factory.getUpdatedAt());
    }

    private static FactoryCalendar toCalendar(Factory factory) {
        return new FactoryCalendar(ZoneId.of(factory.getTimeZone()), sortedShifts(factory).stream()
                .map(shift -> new FactoryCalendar.Shift(shift.getDay(), shift.getStartTime(), shift.getEndTime(),
                        shift.getBreaks().stream()
                                .map(b -> new FactoryCalendar.Break(b.getStartTime(), b.getEndTime()))
                                .sorted(java.util.Comparator.comparing(FactoryCalendar.Break::start))
                                .toList()))
                .toList());
    }

    /** Sorted here as well as by the mapping, because a just-created factory holds insertion order. */
    private static List<WorkingShift> sortedShifts(Factory factory) {
        return factory.getShifts().stream()
                .sorted(java.util.Comparator.comparing(WorkingShift::getDay).thenComparing(WorkingShift::getStartTime))
                .toList();
    }

    private static boolean isZone(String value) {
        try {
            ZoneId.of(value);
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }

    private static boolean isCurrency(String value) {
        try {
            Currency.getInstance(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
