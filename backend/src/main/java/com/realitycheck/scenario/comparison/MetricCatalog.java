package com.realitycheck.scenario.comparison;

import com.realitycheck.scenario.comparison.DeltaCalculator.BetterWhen;
import com.realitycheck.scenario.comparison.RunResultsPort.SummaryView;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/** The one place that defines which headline metrics a comparison reports, with unit and direction. */
public final class MetricCatalog {

    /**
     * @param betterWhen null when neither direction is better: higher utilization is not
     *                   inherently good, and processing time grows with the work done
     */
    public record Metric(String key, String label, String unit, BetterWhen betterWhen,
            Function<SummaryView, BigDecimal> extractor) {
    }

    public static final List<Metric> METRICS = List.of(
            new Metric("elapsedSeconds", "Completion time", "seconds", BetterWhen.LOWER,
                    s -> BigDecimal.valueOf(s.elapsedSeconds())),
            new Metric("processingSeconds", "Processing time", "seconds", null,
                    s -> BigDecimal.valueOf(s.processingSeconds())),
            new Metric("waitingWorkingSeconds", "Working time spent waiting", "seconds", BetterWhen.LOWER,
                    s -> BigDecimal.valueOf(s.waitingWorkingSeconds())),
            new Metric("throughputStonesPerHour", "Throughput", "stones/hour", BetterWhen.HIGHER,
                    SummaryView::throughputStonesPerHour),
            new Metric("outputStones", "Output stones", "stones", BetterWhen.HIGHER, SummaryView::outputStones),
            new Metric("outputCarats", "Output carats", "carats", BetterWhen.HIGHER, SummaryView::outputCarats),
            new Metric("cost.total", "Total cost", "currency", BetterWhen.LOWER, SummaryView::costTotal),
            new Metric("cost.perOutputStone", "Cost per output stone", "currency/stone", BetterWhen.LOWER,
                    SummaryView::costPerOutputStone),
            new Metric("cost.employee", "Employee cost", "currency", BetterWhen.LOWER, SummaryView::costEmployee),
            new Metric("cost.machine", "Machine cost", "currency", BetterWhen.LOWER, SummaryView::costMachine),
            new Metric("cost.fixed", "Fixed cost", "currency", BetterWhen.LOWER, SummaryView::costFixed),
            new Metric("machineUtilization", "Machine utilization", "fraction", null,
                    SummaryView::machineUtilization),
            new Metric("employeeUtilization", "Employee utilization", "fraction", null,
                    SummaryView::employeeUtilization),
            new Metric("ordersCompleted", "Orders completed", "orders", BetterWhen.HIGHER,
                    s -> BigDecimal.valueOf(s.ordersCompleted())),
            new Metric("ordersDelayed", "Orders delayed", "orders", BetterWhen.LOWER,
                    s -> BigDecimal.valueOf(s.ordersDelayed())),
            new Metric("onTimeDeliveryRate", "On-time delivery rate", "fraction", BetterWhen.HIGHER,
                    SummaryView::onTimeDeliveryRate),
            // Algorithm 2.0.0 (spec 11): null for older runs, so their difference is null too.
            new Metric("batchLeadMedianSeconds", "Median batch lead time", "seconds", BetterWhen.LOWER,
                    s -> seconds(s.batchLeadMedianSeconds())),
            new Metric("batchManufacturingMedianSeconds", "Median lead time without parking", "seconds",
                    BetterWhen.LOWER, s -> seconds(s.batchManufacturingMedianSeconds())));

    private static BigDecimal seconds(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private MetricCatalog() {
    }
}
