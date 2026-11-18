package com.realitycheck.production.service;

import com.realitycheck.factory.service.FactoryResolver;
import com.realitycheck.production.api.ProductionDtos;
import com.realitycheck.production.api.ProductionDtos.DispatchWait;
import com.realitycheck.production.api.ProductionDtos.InsightsResponse;
import com.realitycheck.production.api.ProductionDtos.LeadTime;
import com.realitycheck.production.api.ProductionDtos.StageTime;
import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.domain.OperationalEvent;
import com.realitycheck.production.domain.ProductionBatch;
import com.realitycheck.production.persistence.OperationalEventRepository;
import com.realitycheck.production.persistence.ProductionBatchRepository;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.RouteView;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where time goes, from recorded history only (spec 11, task 12): for each stage of the active workflow, how
 * long packets waited there and how long they were worked or held, what went in and came out; the lead time
 * of packets that finished, by route, with and without parking; and how long finished lots waited to be
 * dispatched. Read-only, and nothing is simulated or estimated.
 *
 * <p>A stage is <em>haunted</em> when packets lose a large share of all their time there without being
 * worked on: waiting in its queue, or held at a parking, waiting or job-work stage. The three stages with
 * the largest such share, each at least 5% of all recorded packet time, are ranked.
 */
@Service
@Transactional(readOnly = true)
public class InsightsService {

    static final BigDecimal HAUNTED_THRESHOLD = new BigDecimal("0.05");
    static final int HAUNTED_STAGES = 3;
    private static final StageTimes.Totals NONE = new StageTimes.Totals(0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);

    private final OperationalEventRepository events;
    private final ProductionBatchRepository batches;
    private final WorkflowReader workflowReader;
    private final FactoryResolver factoryResolver;
    private final Clock clock;

    public InsightsService(OperationalEventRepository events, ProductionBatchRepository batches,
            WorkflowReader workflowReader, FactoryResolver factoryResolver, Clock clock) {
        this.events = events;
        this.batches = batches;
        this.workflowReader = workflowReader;
        this.factoryResolver = factoryResolver;
        this.clock = clock;
    }

    /** @param untilOrNull the end of the window; now when null */
    public InsightsResponse insights(UUID factoryId, Instant untilOrNull) {
        factoryResolver.require(factoryId);
        Instant until = untilOrNull == null ? clock.instant() : untilOrNull;
        WorkflowReader.ActiveWorkflow workflow = workflowReader.activeWorkflow(factoryId);
        List<StageView> stages = workflow.stages();

        Map<UUID, List<BatchHistory.Event>> byBatch = new LinkedHashMap<>();
        Map<UUID, Instant> orderCompleted = new HashMap<>();
        Map<UUID, Instant> orderDispatched = new HashMap<>();
        Instant first = null;
        // Corrections come whenever they were recorded: they apply to what happened before `until`.
        for (OperationalEvent event : events.findForStateAt(factoryId, until)) {
            boolean step = event.getEventType() != EventType.CORRECTION;
            if (step && (first == null || event.getOccurredAt().isBefore(first))) {
                first = event.getOccurredAt();
            }
            if (event.getBatchId() != null) {
                byBatch.computeIfAbsent(event.getBatchId(), id -> new ArrayList<>())
                        .add(EventAppendService.toHistory(event));
            } else if (event.getEventType() == EventType.ORDER_COMPLETED) {
                orderCompleted.put(event.getOrderId(), event.getOccurredAt());
            } else if (event.getEventType() == EventType.ORDER_DISPATCHED) {
                orderDispatched.put(event.getOrderId(), event.getOccurredAt());
            }
        }

        Map<UUID, ProductionBatch> batchById = byBatch.isEmpty() ? Map.of()
                : batches.findAllById(byBatch.keySet()).stream()
                        .collect(Collectors.toMap(ProductionBatch::getId, batch -> batch));
        List<StageTimes.BatchRecord> records = new ArrayList<>();
        byBatch.forEach((id, history) -> {
            ProductionBatch batch = batchById.get(id);
            records.add(new StageTimes.BatchRecord(batch.getRouteCode(),
                    new BigDecimal(batch.getStoneCount()).setScale(4), batch.getRoughCarats(),
                    BatchHistory.effective(history)));
        });
        Set<UUID> parking = stages.stream().filter(stage -> "PARKING".equals(stage.kind())).map(StageView::id)
                .collect(Collectors.toSet());
        StageTimes.Result result = StageTimes.compute(records, parking, until);

        return new InsightsResponse(factoryId, first, until, totalSeconds(result),
                stageTimes(stages, result), leadTimes(workflow.routes(), result.journeys()),
                dispatch(orderCompleted, orderDispatched), ProductionDtos.ACTUAL);
    }

    private static long totalSeconds(StageTimes.Result result) {
        return result.stages().values().stream().mapToLong(StageTimes.Totals::totalSeconds).sum();
    }

    private static List<StageTime> stageTimes(List<StageView> stages, StageTimes.Result result) {
        Map<UUID, StageTimes.Totals> totals = result.stages();
        long all = totalSeconds(result);
        Map<UUID, Long> idle = new HashMap<>();
        for (StageView stage : stages) {
            StageTimes.Totals t = totals.getOrDefault(stage.id(), NONE);
            idle.put(stage.id(), t.waitingSeconds() + ("WORKING".equals(stage.kind()) ? 0 : t.activeSeconds()));
        }
        List<UUID> haunted = stages.stream()
                .map(StageView::id)
                .filter(id -> {
                    BigDecimal share = share(idle.get(id), all);
                    return share != null && share.compareTo(HAUNTED_THRESHOLD) >= 0;
                })
                .sorted(Comparator.comparing((UUID id) -> idle.get(id)).reversed().thenComparing(id -> id))
                .limit(HAUNTED_STAGES)
                .toList();

        List<StageTime> stageTimes = new ArrayList<>();
        for (StageView stage : stages) {
            StageTimes.Totals t = totals.getOrDefault(stage.id(), NONE);
            int rank = haunted.indexOf(stage.id());
            stageTimes.add(new StageTime(stage.id(), stage.code(), stage.name(), stage.sequence(), stage.kind(),
                    t.visits(), t.waitingSeconds(), t.activeSeconds(), share(t.totalSeconds(), all),
                    share(idle.get(stage.id()), all), rank < 0 ? null : rank + 1, t.completedVisits(),
                    t.stonesIn().setScale(4, RoundingMode.HALF_EVEN), t.stonesOut().setScale(4, RoundingMode.HALF_EVEN),
                    t.caratsIn().setScale(4, RoundingMode.HALF_EVEN), t.caratsOut().setScale(4, RoundingMode.HALF_EVEN),
                    ratio(t.stonesOut(), t.stonesIn()), ratio(t.caratsOut(), t.caratsIn())));
        }
        return stageTimes;
    }

    /** All packets first, then each route in the workflow's order, then packets without a route if any. */
    private static List<LeadTime> leadTimes(List<RouteView> routes, List<StageTimes.Journey> journeys) {
        List<LeadTime> rows = new ArrayList<>();
        rows.add(lead(null, "All packets", journeys, journey -> true));
        for (RouteView route : routes) {
            rows.add(lead(route.code(), route.name(), journeys, journey -> route.code().equals(journey.routeCode())));
        }
        if (!routes.isEmpty() && journeys.stream().anyMatch(journey -> journey.routeCode() == null)) {
            rows.add(lead(null, "No route", journeys, journey -> journey.routeCode() == null));
        }
        return rows;
    }

    private static LeadTime lead(String code, String name, List<StageTimes.Journey> journeys,
            Predicate<StageTimes.Journey> include) {
        List<StageTimes.Journey> mine = journeys.stream().filter(include).toList();
        long lead = mine.stream().mapToLong(StageTimes.Journey::leadSeconds).sum();
        long parked = mine.stream().mapToLong(StageTimes.Journey::parkingSeconds).sum();
        return new LeadTime(code, name, mine.size(),
                lowerMedian(mine.stream().map(StageTimes.Journey::leadSeconds).toList()),
                lowerMedian(mine.stream().map(j -> j.leadSeconds() - j.parkingSeconds()).toList()),
                share(parked, lead));
    }

    private static DispatchWait dispatch(Map<UUID, Instant> completed, Map<UUID, Instant> dispatched) {
        List<Long> waits = completed.entrySet().stream()
                .map(entry -> {
                    Instant left = dispatched.get(entry.getKey());
                    return left == null ? null : left.getEpochSecond() - entry.getValue().getEpochSecond();
                })
                .filter(Objects::nonNull)
                .toList();
        long waiting = completed.keySet().stream().filter(order -> !dispatched.containsKey(order)).count();
        return new DispatchWait(waits.size(), lowerMedian(waits), waiting);
    }

    /** The lower middle value, so it is a time some packet actually took; null for none. */
    static Long lowerMedian(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get((sorted.size() - 1) / 2);
    }

    /** {@code part / whole} at scale 4, half-even; null when there is no time at all. */
    private static BigDecimal share(long part, long whole) {
        return whole == 0 ? null
                : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal ratio(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? null : part.divide(whole, 4, RoundingMode.HALF_EVEN);
    }
}
