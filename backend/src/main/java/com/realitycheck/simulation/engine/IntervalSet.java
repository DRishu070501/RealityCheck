package com.realitycheck.simulation.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * An immutable set of half-open intervals {@code [start, end)} over whole seconds, kept sorted,
 * non-empty, non-overlapping and non-adjacent, so that two sets covering the same seconds are
 * equal.
 */
final class IntervalSet {

    static final IntervalSet EMPTY = new IntervalSet(List.of());

    record Interval(long start, long end) {

        long length() {
            return end - start;
        }
    }

    private final List<Interval> intervals;

    private IntervalSet(List<Interval> normalised) {
        this.intervals = List.copyOf(normalised);
    }

    /** Builds a set from intervals in any order; empty ones are dropped, touching ones merged. */
    static IntervalSet of(List<Interval> raw) {
        List<Interval> sorted = new ArrayList<>();
        for (Interval interval : raw) {
            if (interval.end() > interval.start()) {
                sorted.add(interval);
            }
        }
        sorted.sort(Comparator.comparingLong(Interval::start).thenComparingLong(Interval::end));
        List<Interval> merged = new ArrayList<>();
        for (Interval interval : sorted) {
            if (!merged.isEmpty() && interval.start() <= merged.get(merged.size() - 1).end()) {
                Interval last = merged.remove(merged.size() - 1);
                merged.add(new Interval(last.start(), Math.max(last.end(), interval.end())));
            } else {
                merged.add(interval);
            }
        }
        return new IntervalSet(merged);
    }

    static IntervalSet of(long start, long end) {
        return of(List.of(new Interval(start, end)));
    }

    List<Interval> intervals() {
        return intervals;
    }

    boolean isEmpty() {
        return intervals.isEmpty();
    }

    long totalLength() {
        long total = 0;
        for (Interval interval : intervals) {
            total += interval.length();
        }
        return total;
    }

    boolean contains(long instant) {
        for (Interval interval : intervals) {
            if (interval.start() > instant) {
                return false;
            }
            if (instant < interval.end()) {
                return true;
            }
        }
        return false;
    }

    IntervalSet union(IntervalSet other) {
        List<Interval> all = new ArrayList<>(intervals);
        all.addAll(other.intervals);
        return of(all);
    }

    /** The seconds of this set that are not in {@code other}. */
    IntervalSet subtract(IntervalSet other) {
        if (other.isEmpty() || isEmpty()) {
            return this;
        }
        List<Interval> result = new ArrayList<>();
        for (Interval interval : intervals) {
            long cursor = interval.start();
            for (Interval cut : other.intervals) {
                if (cut.end() <= cursor) {
                    continue;
                }
                if (cut.start() >= interval.end()) {
                    break;
                }
                if (cut.start() > cursor) {
                    result.add(new Interval(cursor, cut.start()));
                }
                cursor = Math.max(cursor, cut.end());
            }
            if (cursor < interval.end()) {
                result.add(new Interval(cursor, interval.end()));
            }
        }
        return new IntervalSet(result);
    }

    /** The seconds of this set that lie in {@code [from, to)}. */
    IntervalSet clip(long from, long to) {
        List<Interval> result = new ArrayList<>();
        for (Interval interval : intervals) {
            long start = Math.max(interval.start(), from);
            long end = Math.min(interval.end(), to);
            if (end > start) {
                result.add(new Interval(start, end));
            }
        }
        return new IntervalSet(result);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IntervalSet that && intervals.equals(that.intervals);
    }

    @Override
    public int hashCode() {
        return intervals.hashCode();
    }

    @Override
    public String toString() {
        return intervals.toString();
    }
}
