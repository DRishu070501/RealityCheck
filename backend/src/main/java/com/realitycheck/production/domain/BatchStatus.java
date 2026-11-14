package com.realitycheck.production.domain;

public enum BatchStatus {
    /** Waiting in the queue of its current stage. */
    QUEUED,
    /** Being worked on at its current stage. */
    IN_PROGRESS,
    /** Finished the last stage. */
    COMPLETED
}
