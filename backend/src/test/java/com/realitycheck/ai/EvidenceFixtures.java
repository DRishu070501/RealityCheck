package com.realitycheck.ai;

import com.realitycheck.ai.evidence.RunInput;
import com.realitycheck.scenario.api.ComparisonDtos.AlternativeComparison;
import com.realitycheck.scenario.api.ComparisonDtos.BottleneckChange;
import com.realitycheck.scenario.api.ComparisonDtos.BottleneckStage;
import com.realitycheck.scenario.api.ComparisonDtos.ComparedRun;
import com.realitycheck.scenario.api.ComparisonDtos.ComparisonResponse;
import com.realitycheck.scenario.api.ComparisonDtos.Delta;
import com.realitycheck.scenario.api.ComparisonDtos.MetricDelta;
import com.realitycheck.scenario.api.ComparisonDtos.OrderComparison;
import com.realitycheck.scenario.api.ComparisonDtos.ResourceComparison;
import com.realitycheck.scenario.api.ComparisonDtos.StageComparison;
import com.realitycheck.scenario.comparison.DeltaCalculator;
import com.realitycheck.scenario.comparison.DeltaCalculator.BetterWhen;
import com.realitycheck.simulation.api.SimulationDtos.Bottleneck;
import com.realitycheck.simulation.api.SimulationDtos.BottleneckEvidence;
import com.realitycheck.simulation.api.SimulationDtos.Cost;
import com.realitycheck.simulation.api.SimulationDtos.OrderResult;
import com.realitycheck.simulation.api.SimulationDtos.PoolResult;
import com.realitycheck.simulation.api.SimulationDtos.StageResult;
import com.realitycheck.simulation.api.SimulationDtos.Summary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

/**
 * Stored-result shapes for AI tests: a fixed two-stage run ({@link #run()}), a baseline
 * ({@link #baseline()}), their comparison, and random variants for property tests.
 */
public final class EvidenceFixtures {

    public static final Instant FORK = Instant.parse("2026-11-03T00:30:00Z");
    public static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    public static final UUID BASELINE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    public static final UUID CUTTING = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    public static final UUID POLISHING = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    public static final UUID SAWS = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    public static final UUID WHEELS = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    public static final UUID SPARE = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

    private EvidenceFixtures() {
    }

    /** "Add two polishing machines": 52 hours, polishing the bottleneck at 96.2%, one order late. */
    public static RunInput run() {
        return new Params(RUN_ID, "Add two polishing machines", 187_200, "1234567.89", "382.50", "0.962",
                "0.31", 1, "14400", List.of("Wheels capacity 2 → 4")).build();
    }

    /** "Current factory operations": slower, cheaper, two orders late. */
    public static RunInput baseline() {
        return new Params(BASELINE_ID, "Current factory operations", 230_400, "1200000.00", "371.75", "0.990",
                "0.28", 2, "21600", List.of()).build();
    }

    /** Random but well-formed results; the same seed gives the same run. */
    public static RunInput random(long seed, UUID runId, String name) {
        Random r = new Random(seed);
        return new Params(runId, name, 3_600L + r.nextInt(2_000_000),
                money(r, 10_000, 5_000_000), money(r, 10, 2_000), fraction(r), fraction(r), r.nextInt(6),
                String.valueOf(r.nextInt(80_000)), r.nextBoolean() ? List.of("Saws capacity 1 → " + (2 + r.nextInt(5)))
                        : List.of()).build();
    }

    /** What ComparisonService would report for these runs (subtraction only, as it does). */
    public static ComparisonResponse comparison(RunInput run, RunInput baseline) {
        List<MetricDelta> metrics = List.of(
                metric("elapsedSeconds", "Completion time", "seconds", BetterWhen.LOWER, run, baseline,
                        s -> BigDecimal.valueOf(s.elapsedSeconds())),
                metric("cost.total", "Total cost", "currency", BetterWhen.LOWER, run, baseline,
                        s -> s.cost().total()),
                metric("cost.perOutputStone", "Cost per output stone", "currency/stone", BetterWhen.LOWER, run,
                        baseline, s -> s.cost().perOutputStone()),
                metric("ordersDelayed", "Orders delayed", "orders", BetterWhen.LOWER, run, baseline,
                        s -> BigDecimal.valueOf(s.ordersDelayed())),
                metric("machineUtilization", "Machine utilization", "fraction", null, run, baseline,
                        Summary::machineUtilization));
        List<StageComparison> stages = new ArrayList<>();
        for (int i = 0; i < run.stages().size(); i++) {
            StageResult a = run.stages().get(i);
            StageResult b = baseline.stages().get(i);
            stages.add(new StageComparison(a.stageId(), a.code(), a.name(), a.sequence(),
                    delta(b.meanWaitWorkingSeconds(), a.meanWaitWorkingSeconds()),
                    delta(b.meanQueueLength(), a.meanQueueLength()),
                    delta(BigDecimal.valueOf(b.maxQueueLength()), BigDecimal.valueOf(a.maxQueueLength())),
                    delta(b.throughputStonesPerHour(), a.throughputStonesPerHour()),
                    delta(b.bottleneck().limitingPoolUtilization(), a.bottleneck().limitingPoolUtilization()),
                    delta(b.bottleneck().score(), a.bottleneck().score())));
        }
        List<ResourceComparison> resources = new ArrayList<>();
        for (int i = 0; i < run.pools().size(); i++) {
            PoolResult a = run.pools().get(i);
            PoolResult b = baseline.pools().get(i);
            resources.add(new ResourceComparison(a.resourceId(), a.name(), a.kind(),
                    delta(BigDecimal.valueOf(b.capacity()), BigDecimal.valueOf(a.capacity())),
                    delta(b.utilization(), a.utilization()), delta(BigDecimal.valueOf(b.busyUnitSeconds()),
                    BigDecimal.valueOf(a.busyUnitSeconds())), delta(BigDecimal.valueOf(b.idleUnitSeconds()),
                    BigDecimal.valueOf(a.idleUnitSeconds())), delta(b.cost(), a.cost())));
        }
        List<OrderComparison> orders = new ArrayList<>();
        for (int i = 0; i < run.orders().size(); i++) {
            OrderResult a = run.orders().get(i);
            OrderResult b = baseline.orders().get(i);
            Long diff = a.completesAt() == null || b.completesAt() == null ? null
                    : a.completesAt().getEpochSecond() - b.completesAt().getEpochSecond();
            orders.add(new OrderComparison(a.orderId(), a.orderNumber(), a.dueAt(), b.completesAt(), a.completesAt(),
                    diff, b.outcome(), a.outcome()));
        }
        boolean same = run.bottleneck().stageId().equals(baseline.bottleneck().stageId());
        BottleneckChange change = new BottleneckChange(same ? "SAME" : "MOVED",
                new BottleneckStage(baseline.bottleneck().stageId(), baseline.bottleneck().stageCode(),
                        baseline.bottleneck().stageName(), "0.80", "0.70"),
                new BottleneckStage(run.bottleneck().stageId(), run.bottleneck().stageCode(),
                        run.bottleneck().stageName(), "0.70", "0.80"));
        ComparedRun b = new ComparedRun(baseline.runId(), UUID.randomUUID(), baseline.scenarioName(), "1.0.0",
                "COMPLETED", List.of());
        ComparedRun a = new ComparedRun(run.runId(), UUID.randomUUID(), run.scenarioName(), "1.0.0", "COMPLETED",
                List.of());
        return new ComparisonResponse("SIMULATED", "INR", true, FORK, List.of(), b,
                List.of(new AlternativeComparison(a, metrics, change, stages, resources, orders)), null);
    }

    // ---- Construction ------------------------------------------------------------------------

    private record Params(UUID runId, String name, long elapsed, String costTotal, String perStone,
            String polishUtil, String spareUtil, int lateOrders, String polishWait, List<String> changes) {

        RunInput build() {
            BigDecimal util = new BigDecimal(polishUtil);
            BigDecimal wait = new BigDecimal(polishWait);
            Summary summary = new Summary(elapsed, elapsed / 2, 90_000, 40_000, 60_000, new BigDecimal("3300"),
                    new BigDecimal("3228.4"), new BigDecimal("3228"), new BigDecimal("4100"), new BigDecimal("2500.25"),
                    new BigDecimal("0.978"), new BigDecimal("0.61"), new BigDecimal("33.6"), new BigDecimal("0.74"),
                    new BigDecimal("0.66"), new Cost(new BigDecimal("800000.00"), new BigDecimal("300000.00"),
                    new BigDecimal("12000.00"), new BigDecimal(costTotal), new BigDecimal(perStone), "INR"),
                    10, 10 - lateOrders, lateOrders, 10,
                    BigDecimal.valueOf(10 - lateOrders).divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP),
                    null, null, null, null, null, null);
            List<StageResult> stages = List.of(
                    stage(CUTTING, "CUTTING", "Cutting", 1, SAWS, "Saws", new BigDecimal("0.71"),
                            new BigDecimal("1800"), new BigDecimal("0.40")),
                    stage(POLISHING, "POLISHING", "Polishing", 2, WHEELS, "Wheels", util, wait,
                            new BigDecimal("0.88")));
            List<PoolResult> pools = List.of(
                    pool(SAWS, "Saws", "MACHINE", 1, new BigDecimal("0.71"), "60000.00"),
                    pool(WHEELS, "Wheels", "MACHINE", 4, util, "240000.00"),
                    pool(SPARE, "Spare", "MACHINE", 1, new BigDecimal(spareUtil), "5000.00"));
            List<OrderResult> orders = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                boolean late = i <= lateOrders;
                Instant due = FORK.plusSeconds(86_400L * i);
                orders.add(new OrderResult(new UUID(0, 1000 + i), "SDW-2026-%04d".formatted(i),
                        late ? due.plusSeconds(7_200) : due.minusSeconds(3_600), due, late ? "LATE" : "ON_TIME",
                        late));
            }
            Bottleneck bottleneck = new Bottleneck(POLISHING, "POLISHING", "Polishing", "CONSTRAINED_POOL_WITH_QUEUE",
                    "Polishing is the bottleneck: work waited behind full slots for 88% of the time the stage had"
                            + " work; its most used pool, Wheels, was at " + util.movePointRight(2).stripTrailingZeros()
                            .toPlainString() + "% over the whole run.",
                    new BigDecimal("0.50"), new BigDecimal("0.30"));
            return new RunInput(runId, name, "1.0.0", "INR", FORK, "COMPLETED", summary, bottleneck, stages, pools,
                    orders, changes);
        }
    }

    private static StageResult stage(UUID id, String code, String name, int sequence, UUID poolId, String pool,
            BigDecimal util, BigDecimal wait, BigDecimal saturation) {
        return new StageResult(id, code, name, sequence, 20, new BigDecimal("3300"), new BigDecimal("3250"),
                new BigDecimal("50"), new BigDecimal("4100"), new BigDecimal("3000"), new BigDecimal("1100"), 45_000,
                20_000, 30_000, wait, new BigDecimal("9000"), 30_000, new BigDecimal("3.4"), 9,
                new BigDecimal("33.6"), new BigDecimal("4000"), new BottleneckEvidence(poolId, pool, util, util,
                new BigDecimal("0.5"), new BigDecimal("0.6"), saturation, new BigDecimal("0.35"),
                new BigDecimal("0.80")));
    }

    private static PoolResult pool(UUID id, String name, String kind, int capacity, BigDecimal util, String cost) {
        return new PoolResult(id, name, kind, capacity, 100_000, 140_000, 40_000, util, null, false,
                new BigDecimal(cost));
    }

    private static MetricDelta metric(String key, String label, String unit, BetterWhen better, RunInput run,
            RunInput baseline, Function<Summary, BigDecimal> value) {
        BigDecimal b = value.apply(baseline.summary());
        BigDecimal a = value.apply(run.summary());
        BigDecimal abs = DeltaCalculator.absolute(b, a);
        BigDecimal pct = DeltaCalculator.percent(b, a);
        return new MetricDelta(key, label, unit, b.toPlainString(), a.toPlainString(), abs.toPlainString(),
                pct == null ? null : pct.toPlainString(), better, DeltaCalculator.direction(better, abs));
    }

    private static Delta delta(BigDecimal b, BigDecimal a) {
        BigDecimal abs = DeltaCalculator.absolute(b, a);
        BigDecimal pct = DeltaCalculator.percent(b, a);
        return new Delta(b == null ? null : b.toPlainString(), a == null ? null : a.toPlainString(),
                abs == null ? null : abs.toPlainString(), pct == null ? null : pct.toPlainString());
    }

    private static String money(Random r, int min, int max) {
        return BigDecimal.valueOf(min + (long) r.nextInt(max - min) * 100 + r.nextInt(100), 2).toPlainString();
    }

    private static String fraction(Random r) {
        return BigDecimal.valueOf(r.nextInt(1001), 3).toPlainString();
    }
}
