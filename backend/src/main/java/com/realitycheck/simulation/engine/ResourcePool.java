package com.realitycheck.simulation.engine;

import java.util.UUID;

/**
 * The live state of one pool during a run: how many units it has now and how many are held.
 * Capacity can drop below what is held when a window starts while jobs are running; the pool is
 * then over-committed until they finish, and {@link #free()} stays at zero.
 */
final class ResourcePool {

    private final UUID id;
    private int capacity;
    private int allocated;
    private boolean overcommitted;

    ResourcePool(UUID id, int capacity) {
        this.id = id;
        this.capacity = capacity;
    }

    UUID id() {
        return id;
    }

    int capacity() {
        return capacity;
    }

    int allocated() {
        return allocated;
    }

    int free() {
        return Math.max(0, capacity - allocated);
    }

    boolean wasOvercommitted() {
        return overcommitted;
    }

    /** Takes units that {@link #free()} said were there. */
    void allocate(int units) {
        if (units < 0 || units > free()) {
            throw new IllegalStateException("Pool " + id + " cannot give " + units + " units; free " + free());
        }
        allocated += units;
    }

    /** Takes units for a job that was already running at the fork, whatever the capacity is now. */
    void allocateHeld(int units) {
        allocated += units;
        noteOvercommit();
    }

    void release(int units) {
        if (units < 0 || units > allocated) {
            throw new IllegalStateException("Pool " + id + " cannot take back " + units + " units; held "
                    + allocated);
        }
        allocated -= units;
    }

    void setCapacity(int newCapacity) {
        capacity = newCapacity;
        noteOvercommit();
    }

    private void noteOvercommit() {
        if (allocated > capacity) {
            overcommitted = true;
        }
    }
}
