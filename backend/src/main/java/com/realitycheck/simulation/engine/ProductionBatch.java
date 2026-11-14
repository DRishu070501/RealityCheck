package com.realitycheck.simulation.engine;

import java.util.UUID;

/**
 * A batch's identity inside the engine, with what dispatch needs from its order.
 *
 * @param dueTime null when the order has no due date
 */
record ProductionBatch(UUID id, UUID orderId, int priority, Long dueTime) {
}
