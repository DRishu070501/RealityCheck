package com.realitycheck.simulation.engine;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * One batch at one stage: the unit of work. It waits in the stage's queue, then runs on exactly
 * one processing slot until it completes. It is never split, merged or interrupted.
 */
final class ProductionJob {

    /**
     * Who is served first at a stage: higher priority, then earlier due date (none last), then
     * longer in the queue, then batch id so that the order is total.
     */
    static final Comparator<ProductionJob> DISPATCH_ORDER = Comparator
            .comparingInt((ProductionJob job) -> -job.batch.priority())
            .thenComparing(job -> job.batch.dueTime(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(job -> job.queueEntryTime)
            .thenComparing(job -> job.batch.id());

    final ProductionBatch batch;
    /** Zero-based position of the stage in the workflow. */
    final int stageIndex;
    final BigDecimal inputStones;
    final BigDecimal inputCarats;
    /** When it joined the queue; may be before the fork for work that was already waiting. */
    final long queueEntryTime;

    /** When work began; for a job in progress at the fork, when it began in recorded history. */
    long startTime;
    /** Working seconds simulated for this job: setup plus processing, or what remained at the fork. */
    long workSeconds;
    long completionTime;
    boolean inProgressAtFork;
    /** Processing is done; the batch is held at the stage for its handling delay (spec 11, task 8). */
    boolean handling;
    List<ResourceAllocation> allocations = List.of();

    ProductionJob(ProductionBatch batch, int stageIndex, BigDecimal inputStones, BigDecimal inputCarats,
            long queueEntryTime) {
        this.batch = batch;
        this.stageIndex = stageIndex;
        this.inputStones = inputStones;
        this.inputCarats = inputCarats;
        this.queueEntryTime = queueEntryTime;
    }
}
