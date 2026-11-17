package com.realitycheck.production.service;

import com.realitycheck.factory.service.FactoryResolver;
import com.realitycheck.production.api.ProductionDtos;
import com.realitycheck.production.api.ProductionDtos.InsightsResponse;
import com.realitycheck.production.api.ProductionDtos.StageTime;
import com.realitycheck.production.domain.OperationalEvent;
import com.realitycheck.production.persistence.OperationalEventRepository;
import com.realitycheck.workflow.service.WorkflowReader;
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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where time goes, from recorded history only (spec 11): for each stage of the active workflow, how long
 * packets waited there and how long they were worked or held, summed over every visit. Read-only.
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

    private final OperationalEventRepository events;
    private final WorkflowReader workflowReader;
    private final FactoryResolver factoryResolver;
    private final Clock clock;

    public InsightsService(OperationalEventRepository events, WorkflowReader workflowReader,
            FactoryResolver factoryResolver, Clock clock) {
        this.events = events;
        this.workflowReader = workflowReader;
        this.factoryResolver = factoryResolver;
        this.clock = clock;
    }

    /** @param untilOrNull the end of the window; now when null */
    public InsightsResponse insights(UUID factoryId, Instant untilOrNull) {
        factoryResolver.require(factoryId);
        Instant until = untilOrNull == null ? clock.instant() : untilOrNull;
        List<StageView> stages = workflowReader.activeStages(factoryId);

        Map<UUID, List<BatchHistory.Event>> byBatch = new LinkedHashMap<>();
        Instant first = null;
        for (OperationalEvent event : events.findForStateAt(factoryId, until)) {
            if (first == null || event.getOccurredAt().isBefore(first)) {
                first = event.getOccurredAt();
            }
            if (event.getBatchId() != null) {
                byBatch.computeIfAbsent(event.getBatchId(), id -> new ArrayList<>())
                        .add(EventAppendService.toHistory(event));
            }
        }
        List<List<BatchHistory.Event>> histories = byBatch.values().stream().map(BatchHistory::effective).toList();
        Map<UUID, StageTimes.Totals> totals = StageTimes.compute(histories, until);

        long all = totals.values().stream().mapToLong(StageTimes.Totals::totalSeconds).sum();
        Map<UUID, Long> idle = new HashMap<>();
        for (StageView stage : stages) {
            StageTimes.Totals t = totals.getOrDefault(stage.id(), new StageTimes.Totals(0, 0, 0));
            idle.put(stage.id(), t.waitingSeconds() + ("WORKING".equals(stage.kind()) ? 0 : t.activeSeconds()));
        }
        List<UUID> haunted = stages.stream()
                .map(StageView::id)
                .filter(id -> share(idle.get(id), all) != null
                        && share(idle.get(id), all).compareTo(HAUNTED_THRESHOLD) >= 0)
                .sorted(Comparator.comparing((UUID id) -> idle.get(id)).reversed().thenComparing(id -> id))
                .limit(HAUNTED_STAGES)
                .toList();

        List<StageTime> stageTimes = new ArrayList<>();
        for (StageView stage : stages) {
            StageTimes.Totals t = totals.getOrDefault(stage.id(), new StageTimes.Totals(0, 0, 0));
            int rank = haunted.indexOf(stage.id());
            stageTimes.add(new StageTime(stage.id(), stage.code(), stage.name(), stage.sequence(), stage.kind(),
                    t.visits(), t.waitingSeconds(), t.activeSeconds(), share(t.totalSeconds(), all),
                    share(idle.get(stage.id()), all), rank < 0 ? null : rank + 1));
        }
        return new InsightsResponse(factoryId, first, until, all, stageTimes, ProductionDtos.ACTUAL);
    }

    /** {@code part / whole} at scale 4, half-even; null when there is no time at all. */
    private static BigDecimal share(long part, long whole) {
        return whole == 0 ? null
                : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_EVEN);
    }
}
