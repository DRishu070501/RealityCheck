package com.realitycheck.scenario;

import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BatchSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BottleneckConfigSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BreakSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.CalendarSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.LimitsSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.OrderSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.PoolSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.RequirementSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.ShiftSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.StageSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A small factory as a snapshot: Cutting (4 saws, 1 cutter each) then Polishing (6 wheels, 2
 * polishers each, 12 polishers). Monday to Saturday 09:00-18:00 with a 13:00-14:00 break.
 * One batch in progress at cutting, one queued at cutting, one queued at polishing, one future arrival.
 */
public final class SnapshotFixtures {

    public static final UUID FACTORY = UUID.fromString("00000000-0000-0000-0000-00000000000f");
    public static final UUID SAWS = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID WHEELS = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID POLISHERS = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID CUTTING = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    public static final UUID POLISHING = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    public static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID IN_PROGRESS = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    public static final UUID QUEUED_FIRST = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    public static final UUID QUEUED_SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    public static final UUID ARRIVING = UUID.fromString("00000000-0000-0000-0000-0000000000b4");

    /** Monday 12 January 2026, 11:00 in Asia/Kolkata. */
    public static final Instant FORK = Instant.parse("2026-01-12T05:30:00Z");

    private SnapshotFixtures() {
    }

    public static Snapshot base() {
        List<ShiftSnapshot> shifts = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            shifts.add(new ShiftSnapshot(day, LocalTime.of(9, 0), LocalTime.of(18, 0),
                    List.of(new BreakSnapshot(LocalTime.of(13, 0), LocalTime.of(14, 0)))));
        }
        List<StageSnapshot> stages = List.of(
                new StageSnapshot(CUTTING, "CUTTING", "Cutting", 1, new BigDecimal("12.0000"), 1200,
                        new BigDecimal("0.985000"), new BigDecimal("0.620000"), new BigDecimal("200.00"),
                        List.of(new RequirementSnapshot(SAWS, 1)), null, null),
                new StageSnapshot(POLISHING, "POLISHING", "Polishing", 2, new BigDecimal("6.0000"), 900,
                        new BigDecimal("0.990000"), new BigDecimal("0.820000"), new BigDecimal("150.00"),
                        List.of(new RequirementSnapshot(WHEELS, 1), new RequirementSnapshot(POLISHERS, 2)), null, null));
        List<PoolSnapshot> pools = List.of(
                new PoolSnapshot(SAWS, "MACHINE", "CUTTING_LASER", "Laser saws", 4, new BigDecimal("300.00"),
                        List.of()),
                new PoolSnapshot(WHEELS, "MACHINE", "POLISHING_WHEEL", "Polishing wheels", 6,
                        new BigDecimal("140.00"), List.of()),
                new PoolSnapshot(POLISHERS, "EMPLOYEE", "POLISHER", "Polishers", 12, new BigDecimal("230.00"),
                        List.of()));
        List<BatchSnapshot> batches = List.of(
                new BatchSnapshot(IN_PROGRESS, ORDER, 1, BatchSnapshot.IN_PROGRESS, 1, new BigDecimal("40.0000"),
                        new BigDecimal("52.0000"), null, FORK.minusSeconds(1800), null, null),
                new BatchSnapshot(QUEUED_FIRST, ORDER, 2, BatchSnapshot.QUEUED, 1, new BigDecimal("30.0000"),
                        new BigDecimal("39.0000"), FORK.minusSeconds(600), null, null, null),
                new BatchSnapshot(QUEUED_SECOND, ORDER, 3, BatchSnapshot.QUEUED, 2, new BigDecimal("19.7000"),
                        new BigDecimal("16.1200"), FORK.minusSeconds(300), null, null, null),
                new BatchSnapshot(ARRIVING, ORDER, 4, BatchSnapshot.FUTURE_ARRIVAL, 1, new BigDecimal("25.0000"),
                        new BigDecimal("33.3333"), null, null, FORK.plusSeconds(86_400), null));
        return new Snapshot(Snapshot.SCHEMA_VERSION, FACTORY, "Asia/Kolkata", "INR", FORK, new CalendarSnapshot(shifts),
                stages, pools, List.of(new OrderSnapshot(ORDER, "ORD-1", 3, FORK.plusSeconds(5 * 86_400),
                        FORK.minusSeconds(7200))),
                batches, new LimitsSnapshot(180L * 24 * 3600, 1_000_000),
                new BottleneckConfigSnapshot(new BigDecimal("0.35"), new BigDecimal("0.30"), new BigDecimal("0.20"),
                        new BigDecimal("0.15"), new BigDecimal("0.50"), new BigDecimal("0.50")),
                List.of(), List.of());
    }

    public static PoolSnapshot pool(Snapshot snapshot, UUID id) {
        return snapshot.pools().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    public static BatchSnapshot batch(Snapshot snapshot, UUID id) {
        return snapshot.batches().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    public static StageSnapshot stage(Snapshot snapshot, UUID id) {
        return snapshot.stages().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}
