package com.realitycheck.simulation.engine;

import java.util.Comparator;
import java.util.UUID;

/**
 * Something scheduled to happen at a simulated instant. Events are totally ordered by time, then
 * by the priority of their type, then by the sequence number they were scheduled with, so two
 * events never compare equal and the order never depends on anything outside the run.
 *
 * @param subjectId the batch (for completions and arrivals) or pool (for capacity changes); null otherwise
 */
record SimulationEvent(long time, Type type, long seq, UUID subjectId) implements Comparable<SimulationEvent> {

    /**
     * In priority order. At one instant: capacity changes apply first, then completions release
     * their units, then shift starts and arrivals, and last a single dispatch that sees all of it.
     */
    enum Type {
        RESOURCE_CAPACITY_CHANGE,
        STAGE_COMPLETED,
        SHIFT_START,
        JOB_ARRIVAL,
        DISPATCH_ATTEMPT
    }

    private static final Comparator<SimulationEvent> ORDER = Comparator
            .comparingLong(SimulationEvent::time)
            .thenComparingInt((SimulationEvent event) -> event.type().ordinal())
            .thenComparingLong(SimulationEvent::seq);

    @Override
    public int compareTo(SimulationEvent other) {
        return ORDER.compare(this, other);
    }
}
