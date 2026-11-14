package com.realitycheck.resource.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.resource.service.CapacityTimeline.Segment;
import com.realitycheck.resource.service.CapacityTimeline.Window;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class CapacityTimelineProperties {

    private static final Instant T0 = Instant.parse("2026-01-05T00:00:00Z");
    private static final int RANGE_SECONDS = 1_000;

    @Property
    void segmentsCoverTheRangeExactlyWithoutGapsOrOverlaps(
            @ForAll @IntRange(min = 0, max = 50) int base,
            @ForAll("windows") List<Window> windows) {
        Instant from = T0;
        Instant to = T0.plusSeconds(RANGE_SECONDS);

        List<Segment> segments = CapacityTimeline.segments(base, windows, from, to);

        assertThat(segments).isNotEmpty();
        assertThat(segments.get(0).from()).isEqualTo(from);
        assertThat(segments.get(segments.size() - 1).to()).isEqualTo(to);
        for (int i = 0; i < segments.size(); i++) {
            assertThat(segments.get(i).to()).isAfter(segments.get(i).from());
            if (i > 0) {
                assertThat(segments.get(i).from()).isEqualTo(segments.get(i - 1).to());
                assertThat(segments.get(i).capacity()).isNotEqualTo(segments.get(i - 1).capacity());
            }
        }
    }

    @Property
    void capacityIsNeverNegativeAndMatchesThePointwiseDefinition(
            @ForAll @IntRange(min = 0, max = 50) int base,
            @ForAll("windows") List<Window> windows,
            @ForAll @IntRange(min = 0, max = RANGE_SECONDS - 1) int offset) {
        Instant instant = T0.plusSeconds(offset);

        List<Segment> segments = CapacityTimeline.segments(base, windows, T0, T0.plusSeconds(RANGE_SECONDS));
        Segment containing = segments.stream()
                .filter(s -> !s.from().isAfter(instant) && s.to().isAfter(instant))
                .findFirst()
                .orElseThrow();

        assertThat(containing.capacity()).isGreaterThanOrEqualTo(0);
        assertThat(containing.capacity()).isEqualTo(CapacityTimeline.capacityAt(base, windows, instant));
    }

    @Property
    void removingUnitsNeverRaisesCapacityAnywhere(
            @ForAll @IntRange(min = 0, max = 50) int base,
            @ForAll("windows") List<Window> windows,
            @ForAll @IntRange(min = 0, max = RANGE_SECONDS - 1) int offset) {
        Instant instant = T0.plusSeconds(offset);
        List<Window> onlyReductions = windows.stream().filter(w -> w.capacityDelta() < 0).toList();

        assertThat(CapacityTimeline.capacityAt(base, onlyReductions, instant)).isLessThanOrEqualTo(base);
    }

    @Provide
    Arbitrary<List<Window>> windows() {
        Arbitrary<Integer> start = Arbitraries.integers().between(-200, RANGE_SECONDS + 100);
        Arbitrary<Integer> length = Arbitraries.integers().between(1, 400);
        Arbitrary<Integer> delta = Arbitraries.integers().between(-20, 20).filter(d -> d != 0);
        return Combinators.combine(start, length, delta)
                .as((s, l, d) -> new Window(T0.plusSeconds(s), T0.plusSeconds(s + l), d))
                .list()
                .ofMaxSize(8);
    }
}
