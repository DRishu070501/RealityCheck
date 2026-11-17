package com.realitycheck.scenario.change;

import com.realitycheck.scenario.change.ChangePayload.Break;
import com.realitycheck.scenario.change.ChangePayload.DemandVolume;
import com.realitycheck.scenario.change.ChangePayload.ProcessingRate;
import com.realitycheck.scenario.change.ChangePayload.ResourceCapacity;
import com.realitycheck.scenario.change.ChangePayload.ResourceDowntime;
import com.realitycheck.scenario.change.ChangePayload.ResourceUnavailable;
import com.realitycheck.scenario.change.ChangePayload.ScheduleMode;
import com.realitycheck.scenario.change.ChangePayload.Shift;
import com.realitycheck.scenario.change.ChangePayload.WorkingSchedule;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.AppliedChange;
import com.realitycheck.scenario.snapshot.Snapshot.BatchSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BreakSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.CalendarSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.CapacityWindowSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.PoolSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.ShiftSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.StageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Applies a scenario's changes to a snapshot, in ordinal order, so a later change sees the effect
 * of earlier ones. A pure function: it never touches the database and never mutates its input;
 * every step builds new records.
 *
 * <p>Rounding: capacity percentages round down to whole units, so capacity is never overstated;
 * demand and rate percentages are exact at scale 4, half-even. Stones are not rounded to integers.
 */
public final class ChangeApplier {

    private static final int QUANTITY_SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static final String NO_EFFECT = "NO_EFFECT";
    public static final String EXCEEDS_CAPACITY = "EXCEEDS_CAPACITY";

    private ChangeApplier() {
    }

    /** One change to apply. */
    public record Change(int ordinal, ChangePayload payload) {
    }

    /** Something the user should know about a change that does not stop the scenario from running. */
    public record Warning(int ordinal, String code, String message) {
    }

    public record Result(Snapshot snapshot, List<Warning> warnings) {
    }

    /** A change describes an impossible configuration in this factory. */
    public static final class ChangeApplicationException extends RuntimeException {

        private final int ordinal;
        private final String field;

        public ChangeApplicationException(int ordinal, String field, String message) {
            super(message);
            this.ordinal = ordinal;
            this.field = field;
        }

        public int ordinal() {
            return ordinal;
        }

        /** The payload field at fault, prefixed with {@code payload.} */
        public String field() {
            return field;
        }
    }

    /**
     * @param base    a snapshot with no applied changes
     * @param changes in any order; applied by ascending ordinal
     */
    public static Result apply(Snapshot base, List<Change> changes) {
        Snapshot current = base;
        List<AppliedChange> applied = new ArrayList<>(base.appliedChanges());
        List<Warning> warnings = new ArrayList<>();
        List<Change> ordered = changes.stream().sorted(Comparator.comparingInt(Change::ordinal)).toList();
        for (Change change : ordered) {
            Step step = applyOne(current, change, warnings);
            current = step.snapshot();
            applied.add(new AppliedChange(change.ordinal(), change.payload().type().name(),
                    ChangePayloads.toJson(change.payload()), step.summary()));
        }
        Snapshot result = new Snapshot(current.schemaVersion(), current.factoryId(), current.timeZone(),
                current.currencyCode(), current.forkAt(), current.calendar(), current.stages(), current.pools(),
                current.orders(), current.batches(), current.limits(), current.bottleneck(), applied,
                current.routes());
        return new Result(result, List.copyOf(warnings));
    }

    private record Step(Snapshot snapshot, String summary) {
    }

    private static Step applyOne(Snapshot s, Change change, List<Warning> warnings) {
        int ordinal = change.ordinal();
        ChangeSummaries names = new ChangeSummaries(s);
        return switch (change.payload()) {
            case ResourceCapacity c -> {
                PoolSnapshot pool = pool(s, c.resourceId(), ordinal);
                int before = pool.baseCapacity();
                int after = switch (c.mode()) {
                    case ABSOLUTE -> c.value().intValueExact();
                    case DELTA -> Math.max(0, before + c.value().intValueExact());
                    case PERCENT -> Math.max(0, BigDecimal.valueOf(before).multiply(HUNDRED.add(c.value()))
                            .divide(HUNDRED, 0, RoundingMode.FLOOR).intValueExact());
                };
                if (after == before) {
                    warnings.add(new Warning(ordinal, NO_EFFECT, pool.name() + " stays at " + before
                            + " units after this change, so it has no effect"));
                }
                PoolSnapshot changed = new PoolSnapshot(pool.id(), pool.kind(), pool.category(), pool.name(), after,
                        pool.hourlyCost(), pool.windows());
                yield new Step(withPool(s, changed), names.describe(c, before, after));
            }
            case ResourceUnavailable u -> {
                PoolSnapshot pool = pool(s, u.resourceId(), ordinal);
                Instant from = u.from() != null ? u.from() : s.forkAt();
                Instant to = u.to() != null ? u.to() : horizonEnd(s);
                if (!to.isAfter(from)) {
                    throw new ChangeApplicationException(ordinal, "payload.to", "must be after from");
                }
                if (u.units() > pool.baseCapacity()) {
                    warnings.add(new Warning(ordinal, EXCEEDS_CAPACITY, pool.name() + " has " + pool.baseCapacity()
                            + " units, so taking " + u.units() + " out leaves none"));
                }
                yield new Step(withPool(s, withWindow(pool, new CapacityWindowSnapshot(from, to, -u.units()))),
                        names.describe(u));
            }
            case ResourceDowntime d -> {
                PoolSnapshot pool = pool(s, d.resourceId(), ordinal);
                // Large enough to cancel the base capacity and any added units in the period; the
                // engine floors capacity at zero.
                int added = pool.windows().stream()
                        .filter(w -> w.delta() > 0 && w.from().isBefore(d.to()) && w.to().isAfter(d.from()))
                        .mapToInt(CapacityWindowSnapshot::delta)
                        .sum();
                int delta = -(pool.baseCapacity() + added);
                if (delta == 0) {
                    warnings.add(new Warning(ordinal, NO_EFFECT, pool.name() + " has no units to take out"));
                    yield new Step(s, names.describe(d));
                }
                yield new Step(withPool(s, withWindow(pool, new CapacityWindowSnapshot(d.from(), d.to(), delta))),
                        names.describe(d));
            }
            case DemandVolume d -> {
                BigDecimal factor = HUNDRED.add(d.percent());
                int scaled = 0;
                List<BatchSnapshot> batches = new ArrayList<>();
                for (BatchSnapshot batch : s.batches()) {
                    if (unstarted(batch, s)) {
                        batches.add(batch.withQuantities(scale(batch.stones(), factor), scale(batch.carats(), factor)));
                        scaled++;
                    } else {
                        batches.add(batch);
                    }
                }
                if (scaled == 0) {
                    warnings.add(new Warning(ordinal, NO_EFFECT,
                            "No batch is waiting to start its first stage at the fork, so demand is unchanged"));
                }
                yield new Step(new Snapshot(s.schemaVersion(), s.factoryId(), s.timeZone(), s.currencyCode(),
                        s.forkAt(), s.calendar(), s.stages(), s.pools(), s.orders(), batches, s.limits(),
                        s.bottleneck(), s.appliedChanges(), s.routes()), names.describe(d));
            }
            case ProcessingRate r -> {
                StageSnapshot stage = s.stages().stream().filter(st -> st.id().equals(r.stageId())).findFirst()
                        .orElseThrow(() -> new ChangeApplicationException(ordinal, "payload.stageId",
                                "is not a stage of the factory's active workflow"));
                BigDecimal before = stage.stonesPerHour();
                BigDecimal after = switch (r.mode()) {
                    case ABSOLUTE -> r.value().setScale(QUANTITY_SCALE, RoundingMode.HALF_EVEN);
                    case PERCENT -> scale(before, HUNDRED.add(r.value()));
                };
                if (after.signum() <= 0) {
                    throw new ChangeApplicationException(ordinal, "payload.value",
                            "would leave " + stage.name() + " with a rate of zero");
                }
                if (after.compareTo(before) == 0) {
                    warnings.add(new Warning(ordinal, NO_EFFECT, stage.name() + " stays at "
                            + before.stripTrailingZeros().toPlainString() + " stones per hour, so it has no effect"));
                }
                StageSnapshot changed = new StageSnapshot(stage.id(), stage.code(), stage.name(), stage.sequence(),
                        after, stage.setupSeconds(), stage.stoneYield(), stage.caratYield(), stage.fixedCostPerJob(),
                        stage.requirements(), stage.kind(), stage.delayDeciles(), stage.handlingDeciles());
                List<StageSnapshot> stages = s.stages().stream()
                        .map(st -> st.id().equals(stage.id()) ? changed : st)
                        .toList();
                yield new Step(new Snapshot(s.schemaVersion(), s.factoryId(), s.timeZone(), s.currencyCode(),
                        s.forkAt(), s.calendar(), stages, s.pools(), s.orders(), s.batches(), s.limits(),
                        s.bottleneck(), s.appliedChanges(), s.routes()), names.describe(r, before, after));
            }
            case WorkingSchedule w -> {
                List<Shift> combined = new ArrayList<>();
                if (w.mode() == ScheduleMode.ADD) {
                    for (ShiftSnapshot existing : s.calendar().shifts()) {
                        combined.add(new Shift(existing.dayOfWeek(), existing.startTime(), existing.endTime(),
                                existing.breaks().stream().map(b -> new Break(b.startTime(), b.endTime())).toList()));
                    }
                }
                combined.addAll(w.shifts());
                String overlap = ChangePayloads.overlap(combined);
                if (overlap != null) {
                    throw new ChangeApplicationException(ordinal, "payload.shifts",
                            "must not overlap the factory's shifts: " + overlap);
                }
                List<ShiftSnapshot> shifts = combined.stream()
                        .map(shift -> new ShiftSnapshot(shift.dayOfWeek(), shift.startTime(), shift.endTime(),
                                shift.breaksOrEmpty().stream()
                                        .sorted(Comparator.comparing(Break::startTime))
                                        .map(b -> new BreakSnapshot(b.startTime(), b.endTime()))
                                        .toList()))
                        .sorted(Comparator.comparingInt(ShiftSnapshot::dayOfWeek)
                                .thenComparing(ShiftSnapshot::startTime))
                        .toList();
                CalendarSnapshot calendar = new CalendarSnapshot(shifts);
                if (calendar.equals(s.calendar())) {
                    warnings.add(new Warning(ordinal, NO_EFFECT, "The working schedule is unchanged"));
                }
                yield new Step(new Snapshot(s.schemaVersion(), s.factoryId(), s.timeZone(), s.currencyCode(),
                        s.forkAt(), calendar, s.stages(), s.pools(), s.orders(), s.batches(), s.limits(),
                        s.bottleneck(), s.appliedChanges(), s.routes()), names.describe(w));
            }
        };
    }

    /**
     * Batches not yet started on their first stage: queued there, or arriving later. The first stage
     * is the first step of the batch's route, or stage 1 for a batch without one.
     */
    public static boolean unstarted(BatchSnapshot batch, Snapshot snapshot) {
        if (BatchSnapshot.FUTURE_ARRIVAL.equals(batch.state())) {
            return true;
        }
        int first = batch.routeCode() == null ? 1 : snapshot.routes().stream()
                .filter(route -> route.code().equals(batch.routeCode()))
                .findFirst()
                .map(route -> route.steps().isEmpty() ? 1 : route.steps().get(0).stageSequence())
                .orElse(1);
        return BatchSnapshot.QUEUED.equals(batch.state()) && batch.stageSequence() == first;
    }

    private static BigDecimal scale(BigDecimal value, BigDecimal percentFactor) {
        return value.multiply(percentFactor).divide(HUNDRED, QUANTITY_SCALE, RoundingMode.HALF_EVEN);
    }

    private static Instant horizonEnd(Snapshot s) {
        return s.forkAt().plusSeconds(s.limits().maxSimulatedSeconds());
    }

    private static PoolSnapshot pool(Snapshot s, UUID id, int ordinal) {
        return s.pools().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new ChangeApplicationException(ordinal, "payload.resourceId",
                        "is not an active resource pool of this factory"));
    }

    private static PoolSnapshot withWindow(PoolSnapshot pool, CapacityWindowSnapshot window) {
        List<CapacityWindowSnapshot> windows = new ArrayList<>(pool.windows());
        windows.add(window);
        windows.sort(Comparator.comparing(CapacityWindowSnapshot::from)
                .thenComparing(CapacityWindowSnapshot::to)
                .thenComparingInt(CapacityWindowSnapshot::delta));
        return new PoolSnapshot(pool.id(), pool.kind(), pool.category(), pool.name(), pool.baseCapacity(),
                pool.hourlyCost(), windows);
    }

    private static Snapshot withPool(Snapshot s, PoolSnapshot changed) {
        List<PoolSnapshot> pools = s.pools().stream().map(p -> p.id().equals(changed.id()) ? changed : p).toList();
        return new Snapshot(s.schemaVersion(), s.factoryId(), s.timeZone(), s.currencyCode(), s.forkAt(),
                s.calendar(), s.stages(), pools, s.orders(), s.batches(), s.limits(), s.bottleneck(),
                s.appliedChanges(), s.routes());
    }
}
