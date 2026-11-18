package com.realitycheck.simulation.backtest;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.realitycheck.scenario.comparison.RecordedProgressService.RecordedBatch;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BatchSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.StageSnapshot;
import com.realitycheck.simulation.engine.SimulationResult;
import com.realitycheck.simulation.engine.SimulationResult.TraceEvent;
import com.realitycheck.simulation.engine.SimulationResult.TraceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compares one simulation from a past fork with what recorded history shows over the same window, for the
 * same batches: those unfinished at the fork and those that arrived after it. Pure: no Spring, no database.
 *
 * <p>Definitions, the same on both sides:
 * <ul>
 * <li>Finished: batches that finished their path in (fork, until].</li>
 * <li>Lead time: for those batches, from the fork (or the batch's arrival, if later) to finishing; the lower
 * median, so it is a time some batch took.</li>
 * <li>Work in progress: batches at each stage at {@code until}, queued, being worked on or held.</li>
 * </ul>
 * Errors are simulated minus recorded, and that difference over recorded.
 */
public final class BacktestComparison {

    private static final int RATIO_SCALE = 6;

    private BacktestComparison() {
    }

    /**
     * @param percent difference over recorded; null when recorded is zero or either side is missing
     */
    public record Metric(Long simulated, Long recorded, Long difference,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal percent) {

        static Metric of(Long simulated, Long recorded) {
            if (simulated == null || recorded == null) {
                return new Metric(simulated, recorded, null, null);
            }
            long difference = simulated - recorded;
            return new Metric(simulated, recorded, difference, recorded == 0 ? null : ratio(difference, recorded));
        }
    }

    public record StageWip(UUID stageId, String code, String name, int sequence, int simulated, int recorded) {
    }

    /**
     * @param comparedUntil    the end of the window: the simulation's end or the last recorded event, if earlier
     * @param cutAtLastEvent   recorded history ended before the simulation did, so the window stops there
     * @param batches          batches compared: unfinished at the fork or arriving in the window
     * @param wipAbsoluteError the sum over stages of |simulated - recorded|
     * @param wipError         that sum over the recorded total; null when nothing was in progress
     */
    public record ForkComparison(Instant forkAt, Instant comparedUntil, boolean cutAtLastEvent, int batches,
            Metric finished, Metric medianLeadSeconds, List<StageWip> wip, int wipAbsoluteError,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal wipError) {
    }

    public static ForkComparison compare(Snapshot snapshot, SimulationResult result, Instant comparedUntil,
            boolean cutAtLastEvent, Map<UUID, RecordedBatch> recorded) {
        long fork = snapshot.forkAt().getEpochSecond();
        long until = comparedUntil.getEpochSecond();
        Map<Integer, UUID> stageBySequence = new HashMap<>();
        snapshot.stages().forEach(stage -> stageBySequence.put(stage.sequence(), stage.id()));

        // Simulated: where each batch was at `until`, from the last stage it was queued, started or completed at.
        Map<UUID, Long> simulatedFinish = new HashMap<>();
        result.batchFinishes().forEach(finish -> simulatedFinish.put(finish.batchId(), finish.time()));
        Map<UUID, UUID> lastStage = new HashMap<>();
        for (TraceEvent event : result.trace()) {
            if (event.time() > until) {
                break;
            }
            if (event.stageId() != null && event.batchId() != null && event.type() != TraceType.CAPACITY_CHANGED) {
                lastStage.put(event.batchId(), event.stageId());
            }
        }

        Map<UUID, Integer> simulatedWip = new HashMap<>();
        Map<UUID, Integer> recordedWip = new HashMap<>();
        List<Long> simulatedLeads = new ArrayList<>();
        List<Long> recordedLeads = new ArrayList<>();
        int compared = 0;
        for (BatchSnapshot batch : snapshot.batches()) {
            boolean future = BatchSnapshot.FUTURE_ARRIVAL.equals(batch.state());
            long entered = future ? batch.arrivalAt().getEpochSecond() : fork;
            if (entered > until) {
                continue;
            }
            compared++;

            Long finished = simulatedFinish.get(batch.id());
            if (finished != null && finished <= until) {
                simulatedLeads.add(finished - entered);
            } else {
                UUID stage = lastStage.getOrDefault(batch.id(), future ? null : stageBySequence.get(batch.stageSequence()));
                if (stage != null) {
                    simulatedWip.merge(stage, 1, Integer::sum);
                }
            }

            RecordedBatch actual = recorded.get(batch.id());
            if (actual == null) {
                continue;
            }
            if (actual.finishedAt() != null) {
                long at = actual.finishedAt().getEpochSecond();
                if (at > fork && at <= until) {
                    recordedLeads.add(at - Math.min(entered, at));
                }
            } else if (actual.stageId() != null) {
                recordedWip.merge(actual.stageId(), 1, Integer::sum);
            }
        }

        List<StageWip> wip = new ArrayList<>();
        int absoluteError = 0;
        int recordedTotal = 0;
        for (StageSnapshot stage : snapshot.stages().stream().sorted(Comparator.comparingInt(StageSnapshot::sequence)).toList()) {
            int simulated = simulatedWip.getOrDefault(stage.id(), 0);
            int actual = recordedWip.getOrDefault(stage.id(), 0);
            wip.add(new StageWip(stage.id(), stage.code(), stage.name(), stage.sequence(), simulated, actual));
            absoluteError += Math.abs(simulated - actual);
            recordedTotal += actual;
        }

        return new ForkComparison(snapshot.forkAt(), comparedUntil, cutAtLastEvent, compared,
                Metric.of((long) simulatedLeads.size(), (long) recordedLeads.size()),
                Metric.of(lowerMedian(simulatedLeads), lowerMedian(recordedLeads)),
                List.copyOf(wip), absoluteError, recordedTotal == 0 ? null : ratio(absoluteError, recordedTotal));
    }

    /** The lower middle value, so it is a value that occurred; null when there are none. */
    static <T extends Comparable<T>> T lowerMedian(List<T> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<T> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get((sorted.size() - 1) / 2);
    }

    static BigDecimal ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), RATIO_SCALE, RoundingMode.HALF_EVEN);
    }
}
