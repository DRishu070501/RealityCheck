package com.realitycheck.common.api;

import com.realitycheck.common.error.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class Paging {

    private Paging() {
    }

    /** Builds the page request for a list endpoint: zero-based page, size clamped to the allowed range. */
    public static Pageable of(int page, Integer size, Sort sort) {
        if (page < 0) {
            throw new ValidationException("page", "must not be negative");
        }
        return PageRequest.of(page, PageResponse.clampSize(size), sort);
    }
}
