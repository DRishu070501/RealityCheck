package com.realitycheck.simulation.engine;

import java.util.PriorityQueue;
import java.util.UUID;

/** The future event list. Assigns each event the next sequence number as it is scheduled. */
final class SimulationEventQueue {

    private final PriorityQueue<SimulationEvent> events = new PriorityQueue<>();
    private long nextSeq;

    void schedule(long time, SimulationEvent.Type type, UUID subjectId) {
        events.add(new SimulationEvent(time, type, nextSeq++, subjectId));
    }

    SimulationEvent peek() {
        return events.peek();
    }

    SimulationEvent poll() {
        return events.poll();
    }

    boolean isEmpty() {
        return events.isEmpty();
    }
}
