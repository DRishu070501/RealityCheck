package com.realitycheck.simulation.domain;

/**
 * PENDING (submitted) -> RUNNING (claimed by a worker) -> COMPLETED or FAILED. A run that stops on
 * a simulation limit is still COMPLETED as an execution; its termination reason says the work was
 * not finished.
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
