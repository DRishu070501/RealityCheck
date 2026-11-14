package com.realitycheck.production.domain;

public enum EventType {
    /** An order was placed. Recorded by the system when the order is created. */
    ORDER_CREATED,
    /** A batch entered a stage's queue. Recorded by the system, never posted. */
    STAGE_QUEUED,
    /** Work on a batch began at its current stage. */
    STAGE_STARTED,
    /** Work on a batch finished at its current stage; carries the output quantities. */
    STAGE_COMPLETED,
    /** Units of a resource pool became unavailable. Recorded when a negative availability window is created. */
    RESOURCE_UNAVAILABLE,
    /** Every batch of an order finished the last stage. Recorded by the system. */
    ORDER_COMPLETED,
    /** A completed order left the factory. */
    ORDER_DISPATCHED,
    /** Replaces values of an earlier event, or voids it. The earlier row is left untouched. */
    CORRECTION;

    /** The events that move a batch through the workflow. */
    public boolean isBatchStep() {
        return this == STAGE_QUEUED || this == STAGE_STARTED || this == STAGE_COMPLETED;
    }
}
