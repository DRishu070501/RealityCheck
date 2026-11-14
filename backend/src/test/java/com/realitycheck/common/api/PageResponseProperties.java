package com.realitycheck.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class PageResponseProperties {

    @Property
    void totalPagesIsTheSmallestPageCountThatHoldsEveryItem(
            @ForAll @LongRange(min = 0, max = 10_000_000L) long totalItems,
            @ForAll @IntRange(min = 1, max = PageResponse.MAX_SIZE) int size) {
        int pages = PageResponse.of(List.of(), 0, size, totalItems).totalPages();

        assertThat((long) pages * size).isGreaterThanOrEqualTo(totalItems);
        assertThat((long) (pages - 1) * size).isLessThan(Math.max(totalItems, 1));
    }

    @Property
    void clampedSizeIsAlwaysUsable(@ForAll int requested) {
        assertThat(PageResponse.clampSize(requested)).isBetween(1, PageResponse.MAX_SIZE);
    }

    @Example
    void missingSizeFallsBackToTheDefault() {
        assertThat(PageResponse.clampSize(null)).isEqualTo(PageResponse.DEFAULT_SIZE);
    }

    @Example
    void noItemsMeansNoPages() {
        assertThat(PageResponse.of(List.of(), 0, 50, 0).totalPages()).isZero();
    }

    @Example
    void invalidPagingIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> PageResponse.of(List.of(), -1, 50, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> PageResponse.of(List.of(), 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> PageResponse.of(List.of(), 0, 50, -1));
    }
}
