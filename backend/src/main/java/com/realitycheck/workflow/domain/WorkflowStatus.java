package com.realitycheck.workflow.domain;

/** A factory has at most one ACTIVE workflow; it is the one production and simulation use. */
public enum WorkflowStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
