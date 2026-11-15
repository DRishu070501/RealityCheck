package com.realitycheck.scenario.comparison;

import com.realitycheck.production.api.ProductionDtos;
import com.realitycheck.production.api.ProductionDtos.BatchState;
import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.service.FactoryStateService;
import com.realitycheck.production.service.ProductionReader;
import com.realitycheck.production.service.ProductionReader.OrderView;
import com.realitycheck.scenario.api.ComparisonDtos.ActualOrder;
import com.realitycheck.scenario.api.ComparisonDtos.ActualOutcome;
import com.realitycheck.scenario.comparison.RunResultsPort.RunView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads what recorded history shows happened after a run's fork, for the work that run simulated.
 * Everything comes from the factory's state as reconstructed (corrections applied) at the latest
 * recorded event; nothing is estimated and nothing is compared with the simulation here.
 */
@Service
@Transactional(readOnly = true)
public class ActualOutcomeService {

    private final FactoryStateService factoryState;
    private final ProductionReader productionReader;

    public ActualOutcomeService(FactoryStateService factoryState, ProductionReader productionReader) {
        this.factoryState = factoryState;
        this.productionReader = productionReader;
    }

    /** @return empty when nothing was recorded after the run's fork */
    public Optional<ActualOutcome> after(RunView run) {
        Instant asOf = productionReader.lastEventAt(run.factoryId());
        if (asOf == null || !asOf.isAfter(run.forkAt())) {
            return Optional.empty();
        }
        List<UUID> orderIds = run.orders().stream().map(RunResultsPort.OrderView::orderId).toList();
        Map<UUID, OrderView> orders = productionReader.byIds(orderIds);
        Map<UUID, List<BatchState>> batchesByOrder = factoryState.stateAt(run.factoryId(), asOf).batches().stream()
                .filter(batch -> orders.containsKey(batch.orderId()))
                .collect(Collectors.groupingBy(BatchState::orderId));

        BigDecimal stones = BigDecimal.ZERO;
        BigDecimal carats = BigDecimal.ZERO;
        List<ActualOrder> actualOrders = new ArrayList<>();
        int completed = 0;
        for (RunResultsPort.OrderView simulated : run.orders()) {
            OrderView order = orders.get(simulated.orderId());
            List<BatchState> batches = batchesByOrder.getOrDefault(simulated.orderId(), List.of());
            for (BatchState batch : batches) {
                if (finishedAfter(batch, run.forkAt())) {
                    stones = stones.add(batch.inputStones());
                    carats = carats.add(batch.inputCarats());
                }
            }
            boolean allFinished = order != null && !order.batches().isEmpty()
                    && batches.size() == order.batches().size()
                    && batches.stream().allMatch(batch -> batch.status() == BatchStatus.COMPLETED);
            Instant completedAt = allFinished
                    ? batches.stream().map(BatchState::completedAt).max(Comparator.naturalOrder()).orElse(null)
                    : null;
            if (completedAt != null) {
                completed++;
            }
            actualOrders.add(new ActualOrder(simulated.orderId(), simulated.orderNumber(), completedAt));
        }
        return Optional.of(new ActualOutcome(ProductionDtos.ACTUAL, asOf, run.orders().size(), completed,
                stones.toPlainString(), carats.toPlainString(), actualOrders));
    }

    private static boolean finishedAfter(BatchState batch, Instant forkAt) {
        return batch.status() == BatchStatus.COMPLETED && batch.completedAt() != null
                && batch.completedAt().isAfter(forkAt);
    }
}
