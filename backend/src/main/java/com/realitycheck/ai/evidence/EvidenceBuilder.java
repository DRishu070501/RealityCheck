package com.realitycheck.ai.evidence;

import static com.realitycheck.ai.evidence.EvidenceFormatter.CARATS;
import static com.realitycheck.ai.evidence.EvidenceFormatter.CURRENCY;
import static com.realitycheck.ai.evidence.EvidenceFormatter.CURRENCY_PER_STONE;
import static com.realitycheck.ai.evidence.EvidenceFormatter.FRACTION;
import static com.realitycheck.ai.evidence.EvidenceFormatter.JOBS;
import static com.realitycheck.ai.evidence.EvidenceFormatter.ORDERS;
import static com.realitycheck.ai.evidence.EvidenceFormatter.PERCENT;
import static com.realitycheck.ai.evidence.EvidenceFormatter.SCORE;
import static com.realitycheck.ai.evidence.EvidenceFormatter.SECONDS;
import static com.realitycheck.ai.evidence.EvidenceFormatter.STONES;
import static com.realitycheck.ai.evidence.EvidenceFormatter.STONES_PER_HOUR;
import static com.realitycheck.ai.evidence.EvidenceFormatter.UNITS;

import com.realitycheck.ai.evidence.Evidence.EvidenceItem;
import com.realitycheck.ai.evidence.Evidence.RunFacts;
import com.realitycheck.scenario.api.ComparisonDtos.AlternativeComparison;
import com.realitycheck.scenario.api.ComparisonDtos.ComparisonResponse;
import com.realitycheck.scenario.api.ComparisonDtos.ComparisonWarning;
import com.realitycheck.scenario.api.ComparisonDtos.Delta;
import com.realitycheck.scenario.api.ComparisonDtos.MetricDelta;
import com.realitycheck.scenario.api.ComparisonDtos.OrderComparison;
import com.realitycheck.scenario.api.ComparisonDtos.ResourceComparison;
import com.realitycheck.scenario.api.ComparisonDtos.StageComparison;
import com.realitycheck.simulation.api.SimulationDtos.OrderResult;
import com.realitycheck.simulation.api.SimulationDtos.PoolResult;
import com.realitycheck.simulation.api.SimulationDtos.StageResult;
import com.realitycheck.simulation.api.SimulationDtos.Summary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds the evidence document from stored results. It copies and formats; the only numbers it
 * creates are the display forms of stored values, and every difference comes from the comparison.
 *
 * <p>Key scheme: {@code run.summary.elapsedSeconds}, {@code run.cost.total},
 * {@code run.stage.POLISHING.meanWaitWorkingSeconds}, {@code run.resource.<id>.utilization},
 * {@code run.order.<orderNumber>.outcome}, {@code run.change.1}; the same under {@code baseline.};
 * differences under {@code delta.} with {@code .absolute} and {@code .percent} suffixes.
 */
public final class EvidenceBuilder {

    /** Orders kept per run: the most delayed first. */
    public static final int MAX_ORDERS = 50;

    private static final Map<String, Integer> OUTCOME_ORDER = Map.of("UNFINISHED", 0, "LATE", 1, "ON_TIME", 2);
    private static final Map<String, String> METRIC_UNITS = Map.of(
            "seconds", SECONDS, "stones/hour", STONES_PER_HOUR, "stones", STONES, "carats", CARATS,
            "currency", CURRENCY, "currency/stone", CURRENCY_PER_STONE, "fraction", FRACTION, "orders", ORDERS);

    private final List<EvidenceItem> items = new ArrayList<>();
    private final String currencyCode;
    private boolean truncated;

    private EvidenceBuilder(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    /**
     * @param baseline   null without a baseline
     * @param comparison the stored comparison of {@code run} against {@code baseline}; null without one
     */
    public static Evidence build(RunInput run, RunInput baseline, ComparisonResponse comparison) {
        EvidenceBuilder builder = new EvidenceBuilder(run.currencyCode());
        builder.addRun("run", run, true);
        if (baseline != null) {
            builder.addRun("baseline", baseline, false);
            if (comparison != null && !comparison.alternatives().isEmpty()) {
                builder.addComparison(run.runId(), comparison, comparison.alternatives().get(0));
            }
        }
        return new Evidence(Evidence.SCHEMA_VERSION, facts(run), baseline == null ? null : facts(baseline),
                builder.truncated, builder.items);
    }

    private static RunFacts facts(RunInput run) {
        return new RunFacts(run.runId(), run.scenarioName(), run.forkAt(), EvidenceFormatter.instant(run.forkAt()),
                run.algorithmVersion(), run.terminationReason(), run.currencyCode(), run.appliedChanges());
    }

    // ---- One run -----------------------------------------------------------------------------

    private void addRun(String p, RunInput run, boolean full) {
        UUID id = run.runId();
        Summary s = run.summary();
        text(p + ".scenario", "Scenario name", run.scenarioName(), id, null, null);
        text(p + ".termination", "How the simulation ended (COMPLETED means all work finished)",
                run.terminationReason(), id, null, null);
        number(p + ".summary.elapsedSeconds", "Completion time, from the fork to the last completion (calendar time)",
                s.elapsedSeconds(), SECONDS, id);
        number(p + ".summary.workingSeconds", "Factory working time within the run", s.workingSeconds(), SECONDS, id);
        number(p + ".summary.processingSeconds", "Processing time, summed over all jobs", s.processingSeconds(),
                SECONDS, id);
        number(p + ".summary.waitingWorkingSeconds", "Working time jobs spent waiting, summed over all jobs",
                s.waitingWorkingSeconds(), SECONDS, id);
        number(p + ".summary.outputStones", "Output stones", s.outputStones(), STONES, id);
        number(p + ".summary.outputCarats", "Output carats", s.outputCarats(), CARATS, id);
        number(p + ".summary.throughputStonesPerHour", "Throughput per working hour", s.throughputStonesPerHour(),
                STONES_PER_HOUR, id);
        number(p + ".summary.stoneYield", "Stone yield (output over input)", s.stoneYield(), FRACTION, id);
        number(p + ".summary.machineUtilization", "Machine utilization", s.machineUtilization(), FRACTION, id);
        number(p + ".summary.employeeUtilization", "Employee utilization", s.employeeUtilization(), FRACTION, id);
        number(p + ".summary.ordersTotal", "Orders in the simulation", s.ordersTotal(), ORDERS, id);
        number(p + ".summary.ordersCompleted", "Orders completed", s.ordersCompleted(), ORDERS, id);
        number(p + ".summary.ordersDelayed", "Orders delayed (late or unfinished)", s.ordersDelayed(), ORDERS, id);
        number(p + ".summary.onTimeDeliveryRate", "On-time delivery rate", s.onTimeDeliveryRate(), FRACTION, id);
        number(p + ".cost.total", "Total cost", s.cost().total(), CURRENCY, id);
        number(p + ".cost.perOutputStone", "Cost per output stone", s.cost().perOutputStone(), CURRENCY_PER_STONE, id);
        number(p + ".cost.employee", "Employee cost", s.cost().employee(), CURRENCY, id);
        number(p + ".cost.machine", "Machine cost", s.cost().machine(), CURRENCY, id);
        number(p + ".cost.fixed", "Fixed cost", s.cost().fixed(), CURRENCY, id);

        var b = run.bottleneck();
        text(p + ".bottleneck.primaryStage", "Primary bottleneck stage",
                b.stageId() == null ? "None" : b.stageName(), id, b.stageId(), null);
        text(p + ".bottleneck.reason", "Bottleneck reason code", b.reasonCode(), id, b.stageId(), null);
        text(p + ".bottleneck.explanation", "Bottleneck explanation written by the simulation's fixed rules",
                b.explanation(), id, b.stageId(), null);

        for (StageResult st : run.stages()) {
            String k = p + ".stage." + keyPart(st.code());
            String n = st.name();
            UUID sid = st.stageId();
            text(k + ".name", "Stage name", n, id, sid, null);
            stageNumber(k + ".meanWaitWorkingSeconds", n + ": mean working wait per job", st.meanWaitWorkingSeconds(),
                    SECONDS, id, sid);
            stageNumber(k + ".meanQueueLength", n + ": mean queue length", st.meanQueueLength(), JOBS, id, sid);
            stageNumber(k + ".maxQueueLength", n + ": longest queue", BigDecimal.valueOf(st.maxQueueLength()), JOBS,
                    id, sid);
            stageNumber(k + ".throughputStonesPerHour", n + ": throughput per working hour",
                    st.throughputStonesPerHour(), STONES_PER_HOUR, id, sid);
            var e = st.bottleneck();
            if (e.limitingPoolName() != null) {
                text(k + ".limitingPool", n + ": its busiest required resource pool", e.limitingPoolName(), id, sid,
                        e.limitingPoolId());
                stageNumber(k + ".limitingUtilization", n + ": utilization of that pool", e.limitingPoolUtilization(),
                        FRACTION, id, sid);
            }
            stageNumber(k + ".saturation", n + ": share of its busy time spent with work waiting and no free slot",
                    e.saturation(), FRACTION, id, sid);
            stageNumber(k + ".blockedShare", n + ": blocked time as a share of all working time", e.blockedShare(),
                    FRACTION, id, sid);
            stageNumber(k + ".bottleneckScore", n + ": bottleneck score (0 to 1)", e.score(), SCORE, id, sid);
            if (full) {
                stageNumber(k + ".jobsCompleted", n + ": jobs completed", BigDecimal.valueOf(st.jobsCompleted()),
                        JOBS, id, sid);
            }
        }
        if (!full) {
            return;
        }
        for (PoolResult pool : run.pools()) {
            String k = p + ".resource." + pool.resourceId();
            String n = pool.name() + " (" + pool.kind().toLowerCase(java.util.Locale.ROOT) + ")";
            text(k + ".name", "Resource pool name (" + pool.kind().toLowerCase(java.util.Locale.ROOT) + ")",
                    pool.name(), id, null, pool.resourceId());
            poolNumber(k + ".capacity", n + ": units", BigDecimal.valueOf(pool.capacity()), UNITS, id, pool);
            if (pool.utilization() != null) {
                poolNumber(k + ".utilization", n + ": utilization", pool.utilization(), FRACTION, id, pool);
            }
            poolNumber(k + ".idleUnitSeconds", n + ": idle unit time", BigDecimal.valueOf(pool.idleUnitSeconds()),
                    SECONDS, id, pool);
            poolNumber(k + ".cost", n + ": cost", pool.cost(), CURRENCY, id, pool);
        }
        List<OrderResult> orders = run.orders().stream()
                .sorted(Comparator.comparing((OrderResult o) -> OUTCOME_ORDER.getOrDefault(o.outcome(), 3))
                        .thenComparing(OrderResult::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(OrderResult::orderNumber))
                .toList();
        if (orders.size() > MAX_ORDERS) {
            truncated = true;
            orders = orders.subList(0, MAX_ORDERS);
        }
        for (OrderResult order : orders) {
            String k = p + ".order." + keyPart(order.orderNumber());
            text(k + ".number", "Order number", order.orderNumber(), id, null, null);
            text(k + ".outcome", "Order " + order.orderNumber() + ": outcome (ON_TIME, LATE or UNFINISHED)",
                    order.outcome(), id, null, null);
            if ("ON_TIME".equals(order.outcome())) {
                continue; // Due and completion times matter for orders that miss them.
            }
            if (order.dueAt() != null) {
                text(k + ".dueAt", "Order " + order.orderNumber() + ": due", EvidenceFormatter.instant(order.dueAt()),
                        id, null, null);
            }
            text(k + ".completesAt", "Order " + order.orderNumber() + ": simulated completion",
                    order.completesAt() == null ? "not finished" : EvidenceFormatter.instant(order.completesAt()),
                    id, null, null);
        }
        for (int i = 0; i < run.appliedChanges().size(); i++) {
            text(p + ".change." + (i + 1), "Scenario change simulated in this run", run.appliedChanges().get(i), id,
                    null, null);
        }
    }

    // ---- Comparison --------------------------------------------------------------------------

    private void addComparison(UUID runId, ComparisonResponse comparison, AlternativeComparison alt) {
        int w = 0;
        for (ComparisonWarning warning : comparison.warnings()) {
            text("delta.warning." + (++w), "Comparison warning " + warning.code(), warning.message(), runId, null,
                    null);
        }
        for (MetricDelta m : alt.metrics()) {
            String unit = METRIC_UNITS.getOrDefault(m.unit(), m.unit());
            String k = "delta.summary." + m.key();
            deltaNumber(k + ".absolute", m.label() + ": difference (scenario minus baseline)", dec(m.absolute()), unit,
                    runId, null, null);
            deltaNumber(k + ".percent", m.label() + ": difference relative to the baseline", dec(m.percent()),
                    PERCENT, runId, null, null);
            if (m.direction() != null && m.absolute() != null) {
                text(k + ".direction", m.label() + ": IMPROVED, WORSENED or UNCHANGED", m.direction().name(), runId,
                        null, null);
            }
        }
        var bc = alt.bottleneck();
        text("delta.bottleneck.change", "Primary bottleneck from baseline to scenario (SAME, MOVED, RESOLVED,"
                + " EMERGED or NONE)", bc.change(), runId, null, null);
        for (StageComparison st : alt.stages()) {
            String k = "delta.stage." + keyPart(st.code());
            String n = st.name();
            stageDelta(k + ".meanWaitWorkingSeconds", n + ": mean working wait per job", st.meanWaitWorkingSeconds(),
                    SECONDS, runId, st.stageId(), true);
            stageDelta(k + ".meanQueueLength", n + ": mean queue length", st.meanQueueLength(), JOBS, runId,
                    st.stageId(), false);
            stageDelta(k + ".throughputStonesPerHour", n + ": throughput", st.throughputStonesPerHour(),
                    STONES_PER_HOUR, runId, st.stageId(), true);
            stageDelta(k + ".limitingUtilization", n + ": utilization of its busiest pool", st.limitingUtilization(),
                    FRACTION, runId, st.stageId(), false);
            stageDelta(k + ".bottleneckScore", n + ": bottleneck score", st.bottleneckScore(), SCORE, runId,
                    st.stageId(), false);
        }
        for (ResourceComparison r : alt.resources()) {
            String k = "delta.resource." + r.resourceId();
            String n = r.name();
            BigDecimal capacity = r.capacity() == null ? null : dec(r.capacity().absolute());
            if (capacity != null && capacity.signum() != 0) {
                deltaNumber(k + ".capacity.absolute", n + ": change in units", capacity, UNITS, runId, null,
                        r.resourceId());
            }
            deltaNumber(k + ".utilization.absolute", n + ": change in utilization",
                    r.utilization() == null ? null : dec(r.utilization().absolute()), FRACTION, runId, null,
                    r.resourceId());
            deltaNumber(k + ".cost.absolute", n + ": change in cost",
                    r.cost() == null ? null : dec(r.cost().absolute()), CURRENCY, runId, null, r.resourceId());
        }
        int kept = 0;
        for (OrderComparison o : alt.orders()) {
            boolean outcomeChanged = !Objects.equals(o.baselineOutcome(), o.alternativeOutcome());
            boolean moved = o.differenceSeconds() != null && o.differenceSeconds() != 0;
            if (!outcomeChanged && !moved) {
                continue;
            }
            if (kept++ >= MAX_ORDERS) {
                truncated = true;
                break;
            }
            String k = "delta.order." + keyPart(o.orderNumber());
            text(k + ".number", "Order number", o.orderNumber(), runId, null, null);
            if (outcomeChanged) {
                text(k + ".outcomeChange", "Order " + o.orderNumber() + ": outcome in baseline, then in scenario",
                        o.baselineOutcome() + " to " + o.alternativeOutcome(), runId, null, null);
            }
            if (moved) {
                deltaNumber(k + ".completionDifferenceSeconds", "Order " + o.orderNumber()
                        + ": completion in the scenario minus completion in the baseline (negative is earlier)",
                        BigDecimal.valueOf(o.differenceSeconds()), SECONDS, runId, null, null);
            }
        }
    }

    // ---- Item helpers ------------------------------------------------------------------------

    private void number(String key, String label, long value, String unit, UUID runId) {
        number(key, label, BigDecimal.valueOf(value), unit, runId);
    }

    private void number(String key, String label, BigDecimal value, String unit, UUID runId) {
        add(key, label, value, unit, false, runId, null, null);
    }

    private void stageNumber(String key, String label, BigDecimal value, String unit, UUID runId, UUID stageId) {
        add(key, label, value, unit, false, runId, stageId, null);
    }

    private void poolNumber(String key, String label, BigDecimal value, String unit, UUID runId, PoolResult pool) {
        add(key, label, value, unit, false, runId, null, pool.resourceId());
    }

    private void deltaNumber(String key, String label, BigDecimal value, String unit, UUID runId, UUID stageId,
            UUID resourceId) {
        add(key, label, value, unit, true, runId, stageId, resourceId);
    }

    private void stageDelta(String key, String label, Delta delta, String unit, UUID runId, UUID stageId,
            boolean withPercent) {
        if (delta == null) {
            return;
        }
        deltaNumber(key + ".absolute", label + ": difference (scenario minus baseline)", dec(delta.absolute()), unit,
                runId, stageId, null);
        if (withPercent) {
            deltaNumber(key + ".percent", label + ": difference relative to the baseline", dec(delta.percent()),
                    PERCENT, runId, stageId, null);
        }
    }

    private void add(String key, String label, BigDecimal value, String unit, boolean delta, UUID runId,
            UUID stageId, UUID resourceId) {
        if (value == null) {
            return;
        }
        items.add(new EvidenceItem(key, label, value, null, unit,
                EvidenceFormatter.display(value, unit, currencyCode, delta), runId, stageId, resourceId));
    }

    private void text(String key, String label, String text, UUID runId, UUID stageId, UUID resourceId) {
        if (text == null || text.isBlank()) {
            return;
        }
        items.add(new EvidenceItem(key, label, null, text, null, List.of(), runId, stageId, resourceId));
    }

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** Keeps keys to letters, digits, dash and underscore. */
    static String keyPart(String raw) {
        return raw == null ? "UNKNOWN" : raw.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
