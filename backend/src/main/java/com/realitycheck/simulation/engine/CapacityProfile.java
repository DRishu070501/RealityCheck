package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.IntervalSet.Interval;
import com.realitycheck.simulation.engine.SimulationInput.CapacityWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A pool's capacity over time: its base capacity plus the deltas of the windows active at each
 * instant, never below zero. Windows are half-open {@code [from, to)}.
 */
final class CapacityProfile {

    /** Constant capacity over {@code [from, to)}. */
    record Segment(long from, long to, int capacity) {
    }

    private final int base;
    private final List<CapacityWindow> windows;

    CapacityProfile(int base, List<CapacityWindow> windows) {
        this.base = base;
        this.windows = List.copyOf(windows);
    }

    int capacityAt(long instant) {
        long capacity = base;
        for (CapacityWindow window : windows) {
            if (window.from() <= instant && instant < window.to()) {
                capacity += window.delta();
            }
        }
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, capacity));
    }

    /** Instants strictly after {@code after} and at or before {@code upTo} where capacity may change. */
    List<Long> boundariesBetween(long after, long upTo) {
        TreeSet<Long> boundaries = new TreeSet<>();
        for (CapacityWindow window : windows) {
            if (window.from() > after && window.from() <= upTo) {
                boundaries.add(window.from());
            }
            if (window.to() > after && window.to() <= upTo) {
                boundaries.add(window.to());
            }
        }
        return List.copyOf(boundaries);
    }

    /** Contiguous segments covering exactly {@code [from, to)}; empty when the range is empty. */
    List<Segment> segments(long from, long to) {
        if (to <= from) {
            return List.of();
        }
        List<Long> points = new ArrayList<>();
        points.add(from);
        points.addAll(boundariesBetween(from, to - 1));
        points.add(to);
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            segments.add(new Segment(points.get(i), points.get(i + 1), capacityAt(points.get(i))));
        }
        return segments;
    }

    /** The periods within {@code [from, to)} in which the pool has no units at all. */
    IntervalSet zeroIntervals(long from, long to) {
        List<Interval> zero = new ArrayList<>();
        for (Segment segment : segments(from, to)) {
            if (segment.capacity() == 0) {
                zero.add(new Interval(segment.from(), segment.to()));
            }
        }
        return IntervalSet.of(zero);
    }

    /** The largest capacity the pool has at any time in {@code [from, to)}. */
    int maxCapacity(long from, long to) {
        int max = 0;
        for (Segment segment : segments(from, to)) {
            max = Math.max(max, segment.capacity());
        }
        return max;
    }
}
