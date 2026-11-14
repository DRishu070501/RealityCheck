package com.realitycheck.resource.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.resource.service.CapacityTimeline.Segment;
import com.realitycheck.resource.service.CapacityTimeline.Window;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CapacityTimelineTest {

    private static final Instant T0 = Instant.parse("2026-01-05T00:00:00Z");

    @Test
    void withoutWindowsTheWholeRangeIsAtBaseCapacity() {
        assertThat(CapacityTimeline.segments(6, List.of(), at(0), at(10)))
                .containsExactly(new Segment(at(0), at(10), 6));
    }

    @Test
    void aWindowInsideTheRangeSplitsItInThree() {
        assertThat(CapacityTimeline.segments(6, List.of(new Window(at(2), at(5), -2)), at(0), at(10)))
                .containsExactly(
                        new Segment(at(0), at(2), 6),
                        new Segment(at(2), at(5), 4),
                        new Segment(at(5), at(10), 6));
    }

    @Test
    void overlappingWindowsAddUp() {
        List<Window> windows = List.of(new Window(at(1), at(6), -2), new Window(at(4), at(8), -1));

        assertThat(CapacityTimeline.segments(6, windows, at(0), at(10))).containsExactly(
                new Segment(at(0), at(1), 6),
                new Segment(at(1), at(4), 4),
                new Segment(at(4), at(6), 3),
                new Segment(at(6), at(8), 5),
                new Segment(at(8), at(10), 6));
    }

    @Test
    void capacityNeverGoesBelowZero() {
        assertThat(CapacityTimeline.segments(2, List.of(new Window(at(2), at(4), -5)), at(0), at(6)))
                .containsExactly(
                        new Segment(at(0), at(2), 2),
                        new Segment(at(2), at(4), 0),
                        new Segment(at(4), at(6), 2));
    }

    @Test
    void aWindowReachingOutsideTheRangeIsClipped() {
        assertThat(CapacityTimeline.segments(6, List.of(new Window(at(-5), at(3), -1)), at(0), at(10)))
                .containsExactly(
                        new Segment(at(0), at(3), 5),
                        new Segment(at(3), at(10), 6));
    }

    @Test
    void adjacentSegmentsWithEqualCapacityAreMerged() {
        List<Window> windows = List.of(new Window(at(2), at(4), -1), new Window(at(4), at(6), -1));

        assertThat(CapacityTimeline.segments(6, windows, at(0), at(10))).containsExactly(
                new Segment(at(0), at(2), 6),
                new Segment(at(2), at(6), 5),
                new Segment(at(6), at(10), 6));
    }

    @Test
    void windowsAreHalfOpen() {
        List<Window> windows = List.of(new Window(at(2), at(4), -1));

        assertThat(CapacityTimeline.capacityAt(6, windows, at(2))).isEqualTo(5);
        assertThat(CapacityTimeline.capacityAt(6, windows, at(4))).isEqualTo(6);
    }

    @Test
    void anEmptyRangeHasNoSegments() {
        assertThat(CapacityTimeline.segments(6, List.of(), at(5), at(5))).isEmpty();
        assertThat(CapacityTimeline.segments(6, List.of(), at(5), at(4))).isEmpty();
    }

    private static Instant at(int hours) {
        return T0.plusSeconds(hours * 3600L);
    }
}
