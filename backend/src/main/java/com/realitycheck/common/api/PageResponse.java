package com.realitycheck.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The envelope every list endpoint returns. {@code page} is zero-based.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public PageResponse {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        if (totalItems < 0) {
            throw new IllegalArgumentException("totalItems must not be negative");
        }
        items = List.copyOf(items);
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        return new PageResponse<>(items, page, size, totalItems, totalPages(totalItems, size));
    }

    public static <E, T> PageResponse<T> from(Page<E> source, Function<E, T> mapper) {
        return of(source.getContent().stream().map(mapper).toList(),
                source.getNumber(), source.getSize(), source.getTotalElements());
    }

    /** Clamps a client-supplied page size into {@code [1, MAX_SIZE]}; null means the default. */
    public static int clampSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_SIZE;
        }
        return Math.max(1, Math.min(MAX_SIZE, requested));
    }

    static int totalPages(long totalItems, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        if (totalItems < 0) {
            throw new IllegalArgumentException("totalItems must not be negative");
        }
        long pages = (totalItems + size - 1) / size;
        return Math.toIntExact(pages);
    }
}
