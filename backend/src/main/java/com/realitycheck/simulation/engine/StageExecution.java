package com.realitycheck.simulation.engine;

import java.math.BigDecimal;
import java.util.List;

/**
 * The record of a finished job, from which the metrics are computed.
 *
 * @param waitFrom             when its simulated wait began: queue entry, or the fork if it was
 *                             already waiting then; equal to {@code startTime} for work in
 *                             progress at the fork, which did not wait in the simulation
 * @param waitingWorkingSeconds working seconds between {@code waitFrom} and {@code startTime}
 * @param workSeconds          working seconds simulated: setup plus processing, or what remained at the fork
 * @param stageWorkingSeconds  working seconds from {@code waitFrom} to completion
 */
record StageExecution(
        ProductionBatch batch,
        int stageIndex,
        long waitFrom,
        long startTime,
        long completionTime,
        long waitingWorkingSeconds,
        long workSeconds,
        long stageWorkingSeconds,
        BigDecimal inputStones,
        BigDecimal outputStones,
        BigDecimal inputCarats,
        BigDecimal outputCarats,
        List<ResourceAllocation> allocations) {
}
