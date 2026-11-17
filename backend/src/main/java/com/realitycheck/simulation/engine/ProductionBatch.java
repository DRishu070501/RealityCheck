package com.realitycheck.simulation.engine;

import java.util.List;
import java.util.UUID;

/**
 * A batch's identity inside the engine, with what dispatch needs from its order.
 *
 * @param dueTime null when the order has no due date
 * @param path    zero-based indexes of the stages it visits, in order; it starts at its entry stage
 */
record ProductionBatch(UUID id, UUID orderId, int priority, Long dueTime, List<Integer> path) {

    ProductionBatch {
        path = List.copyOf(path);
    }

    /** @return the zero-based index of the stage after {@code stageIndex} on this batch's path, or -1 at its end */
    int nextStageAfter(int stageIndex) {
        int position = path.indexOf(stageIndex);
        if (position < 0) {
            throw new IllegalStateException("Batch " + id + " is at a stage that is not on its path");
        }
        return position + 1 < path.size() ? path.get(position + 1) : -1;
    }
}
