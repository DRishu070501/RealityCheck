package com.realitycheck.resource.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Turns a pool's base capacity and its dated windows into the capacity actually available over a
 * time range. Pure and deterministic; the UI and the simulation snapshot both use it, so they
 * cannot disagree about what a window means.
 */
public final class CapacityTimeline {

    private CapacityTimeline() {
    }

    /** A capacity change over the half-open window {@code [startsAt, endsAt)}. */
    public record Window(Instant startsAt, Instant endsAt, int capacityDelta) {
    }

    /** Constant capacity over the half-open interval {@code [from, to)}. */
    public record Segment(Instant from, Instant to, int capacity) {
    }

    /**
     * @return contiguous segments covering exactly {@code [from, to)}, in time order, with adjacent
     *         equal capacities merged. Capacity is {@code base} plus the deltas of the windows
     *         active at that time, never below zero. Empty when the range is empty.
     */
    public static List<Segment> segments(int base, List<Window> windows, Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return List.of();
        }
        TreeSet<Instant> boundaries = new TreeSet<>();
        boundaries.add(from);
        boundaries.add(to);
        for (Window window : windows) {
            if (window.startsAt().isAfter(from) && window.startsAt().isBefore(to)) {
                boundaries.add(window.startsAt());
            }
            if (window.endsAt().isAfter(from) && window.endsAt().isBefore(to)) {
                boundaries.add(window.endsAt());
            }
        }

        List<Segment> segments = new ArrayList<>();
        Instant start = null;
        for (Instant boundary : boundaries) {
            if (start != null) {
                int capacity = capacityAt(base, windows, start);
                Segment last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
                if (last != null && last.capacity() == capacity) {
                    segments.set(segments.size() - 1, new Segment(last.from(), boundary, capacity));
                } else {
                    segments.add(new Segment(start, boundary, capacity));
                }
            }
            start = boundary;
        }
        return List.copyOf(segments);
    }

    /** Capacity at one instant: base plus every window whose half-open range contains it, floored at zero. */
    public static int capacityAt(int base, List<Window> windows, Instant instant) {
        long capacity = base;
        for (Window window : windows) {
            if (!window.startsAt().isAfter(instant) && window.endsAt().isAfter(instant)) {
                capacity += window.capacityDelta();
            }
        }
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, capacity));
    }
}
