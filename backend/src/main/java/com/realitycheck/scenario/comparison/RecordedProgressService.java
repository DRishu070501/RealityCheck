package com.realitycheck.scenario.comparison;

import com.realitycheck.production.api.ProductionDtos.BatchState;
import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.service.FactoryStateService;
import com.realitycheck.production.service.ProductionReader;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where recorded history shows a set of batches at an instant: finished (and when) or at a stage. Read
 * from the factory's state as reconstructed at that instant, corrections applied; nothing is estimated.
 * Used to check simulations against what happened.
 */
@Service
@Transactional(readOnly = true)
public class RecordedProgressService {

    private final FactoryStateService factoryState;
    private final ProductionReader productionReader;

    public RecordedProgressService(FactoryStateService factoryState, ProductionReader productionReader) {
        this.factoryState = factoryState;
        this.productionReader = productionReader;
    }

    /**
     * @param stageId     the stage it was queued at or worked on; null when finished
     * @param finishedAt  when it finished its path; null when not finished
     */
    public record RecordedBatch(UUID batchId, UUID stageId, Instant finishedAt) {
    }

    /** @return null when nothing has been recorded */
    public Instant firstEventAt(UUID factoryId) {
        return productionReader.firstEventAt(factoryId);
    }

    /** @return null when nothing has been recorded */
    public Instant lastEventAt(UUID factoryId) {
        return productionReader.lastEventAt(factoryId);
    }

    /**
     * @return by batch id, the batches of {@code batchIds} that had been placed by {@code at}; a batch whose
     *         order had not been placed yet is absent
     */
    public Map<UUID, RecordedBatch> at(UUID factoryId, Instant at, Set<UUID> batchIds) {
        Map<UUID, RecordedBatch> result = new HashMap<>();
        for (BatchState batch : factoryState.stateAt(factoryId, at).batches()) {
            if (!batchIds.contains(batch.batchId())) {
                continue;
            }
            if (batch.status() == BatchStatus.COMPLETED) {
                result.put(batch.batchId(), new RecordedBatch(batch.batchId(), null, batch.completedAt()));
            } else if (batch.stageId() != null) {
                result.put(batch.batchId(), new RecordedBatch(batch.batchId(), batch.stageId(), null));
            }
        }
        return result;
    }
}
