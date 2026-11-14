package com.realitycheck.production.domain;

public enum OrderStatus {
    /** Placed; no batch has started any stage. */
    PENDING,
    /** At least one batch has started; not all have finished. */
    IN_PROGRESS,
    /** Every batch has finished the last stage. */
    COMPLETED,
    /** Completed and handed over. */
    DISPATCHED
}
