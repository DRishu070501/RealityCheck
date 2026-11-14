package com.realitycheck.simulation.engine;

/** Simulated time in epoch seconds. The only source of "now" inside a run; it never reads a real clock. */
final class SimulationClock {

    private long now;

    SimulationClock(long start) {
        this.now = start;
    }

    long now() {
        return now;
    }

    void advanceTo(long time) {
        if (time < now) {
            throw new IllegalStateException("Simulated time cannot move backwards: " + now + " -> " + time);
        }
        now = time;
    }
}
