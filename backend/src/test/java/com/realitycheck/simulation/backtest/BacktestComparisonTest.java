package com.realitycheck.simulation.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.scenario.comparison.RecordedProgressService.RecordedBatch;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BatchSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.StageSnapshot;
import com.realitycheck.simulation.backtest.BacktestComparison.ForkComparison;
import com.realitycheck.simulation.backtest.BacktestComparison.StageWip;
import com.realitycheck.simulation.engine.SimulationResult;
import com.realitycheck.simulation.engine.SimulationResult.BatchFinish;
import com.realitycheck.simulation.engine.SimulationResult.TraceEvent;
import com.realitycheck.simulation.engine.SimulationResult.TraceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Hand-worked: two stages, four batches, a fork at 0 h and a window to 10 h. */
class BacktestComparisonTest {

    private static final Instant FORK = Instant.parse("2026-01-10T00:00:00Z");
    private static final long F = FORK.getEpochSecond();
    private static final long H = 3_600;
    private static final UUID CUT = id(1);
    private static final UUID POLISH = id(2);
    private static final UUID A = id(11);
    private static final UUID B = id(12);
    private static final UUID C = id(13);
    private static final UUID D = id(14);

    @Test
    void comparesFinishedLeadTimesAndWorkInProgressOverTheSameWindow() {
        // A and B are queued at Cut at the fork; C arrives at 2 h; D arrives at 12 h, after the window.
        Snapshot snapshot = snapshot(List.of(
                batch(A, BatchSnapshot.QUEUED, 1, null),
                batch(B, BatchSnapshot.QUEUED, 1, null),
                batch(C, BatchSnapshot.FUTURE_ARRIVAL, 1, FORK.plusSeconds(2 * H)),
                batch(D, BatchSnapshot.FUTURE_ARRIVAL, 1, FORK.plusSeconds(12 * H))));
        // Simulated: A finishes at 4 h; B moves to Polish at 5 h and is there at 10 h; C finishes at 9 h
        // (lead 7 h from its arrival); B would finish at 11 h, after the window.
        SimulationResult result = result(
                List.of(
                        trace(1, 0, TraceType.JOB_QUEUED, A, CUT),
                        trace(2, 0, TraceType.JOB_QUEUED, B, CUT),
                        trace(3, 2 * H, TraceType.JOB_QUEUED, C, CUT),
                        trace(4, 4 * H, TraceType.JOB_COMPLETED, A, POLISH),
                        trace(5, 5 * H, TraceType.JOB_QUEUED, B, POLISH),
                        trace(6, 9 * H, TraceType.JOB_COMPLETED, C, POLISH),
                        trace(7, 11 * H, TraceType.JOB_COMPLETED, B, POLISH)),
                List.of(new BatchFinish(A, F + 4 * H), new BatchFinish(B, F + 11 * H), new BatchFinish(C, F + 9 * H)));
        // Recorded at 10 h: A finished at 6 h; B still at Cut; C at Polish. D's order was not placed yet.
        Map<UUID, RecordedBatch> recorded = Map.of(
                A, new RecordedBatch(A, null, FORK.plusSeconds(6 * H)),
                B, new RecordedBatch(B, CUT, null),
                C, new RecordedBatch(C, POLISH, null));

        ForkComparison comparison = BacktestComparison.compare(snapshot, result, FORK.plusSeconds(10 * H), true,
                recorded);

        assertThat(comparison.batches()).isEqualTo(3);
        assertThat(comparison.cutAtLastEvent()).isTrue();
        assertThat(comparison.finished().simulated()).isEqualTo(2);
        assertThat(comparison.finished().recorded()).isEqualTo(1);
        assertThat(comparison.finished().difference()).isEqualTo(1);
        assertThat(comparison.finished().percent()).isEqualByComparingTo("1");
        // Simulated leads 4 h (A) and 7 h (C): lower median 4 h. Recorded: A, 6 h.
        assertThat(comparison.medianLeadSeconds().simulated()).isEqualTo(4 * H);
        assertThat(comparison.medianLeadSeconds().recorded()).isEqualTo(6 * H);
        assertThat(comparison.medianLeadSeconds().percent()).isEqualByComparingTo("-0.333333");
        assertThat(comparison.wip()).containsExactly(
                new StageWip(CUT, "CUT", "Cut", 1, 0, 1),
                new StageWip(POLISH, "POLISH", "Polish", 2, 1, 1));
        assertThat(comparison.wipAbsoluteError()).isEqualTo(1);
        assertThat(comparison.wipError()).isEqualByComparingTo("0.5");
    }

    @Test
    void aBatchWithNoEventInTheWindowIsWhereTheForkPlacedIt() {
        Snapshot snapshot = snapshot(List.of(batch(A, BatchSnapshot.IN_PROGRESS, 2, null)));
        SimulationResult result = result(List.of(trace(1, 20 * H, TraceType.JOB_COMPLETED, A, POLISH)),
                List.of(new BatchFinish(A, F + 20 * H)));

        ForkComparison comparison = BacktestComparison.compare(snapshot, result, FORK.plusSeconds(10 * H), false,
                Map.of(A, new RecordedBatch(A, POLISH, null)));

        assertThat(comparison.finished().simulated()).isZero();
        assertThat(comparison.finished().percent()).isNull();
        assertThat(comparison.medianLeadSeconds().simulated()).isNull();
        assertThat(comparison.medianLeadSeconds().difference()).isNull();
        assertThat(comparison.wip().get(1).simulated()).isEqualTo(1);
        assertThat(comparison.wipError()).isEqualByComparingTo("0");
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static Snapshot snapshot(List<BatchSnapshot> batches) {
        List<StageSnapshot> stages = List.of(stage(CUT, "CUT", "Cut", 1), stage(POLISH, "POLISH", "Polish", 2));
        return new Snapshot(Snapshot.SCHEMA_VERSION, id(99), "UTC", "INR", FORK, null, stages, List.of(), List.of(),
                batches, null, null, List.of(), List.of());
    }

    private static StageSnapshot stage(UUID id, String code, String name, int sequence) {
        return new StageSnapshot(id, code, name, sequence, BigDecimal.ONE, 0, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, List.of(), null, null, null);
    }

    private static BatchSnapshot batch(UUID id, String state, int sequence, Instant arrival) {
        return new BatchSnapshot(id, id(50), 1, state, sequence, BigDecimal.TEN, BigDecimal.ONE,
                BatchSnapshot.QUEUED.equals(state) ? FORK : null, BatchSnapshot.IN_PROGRESS.equals(state) ? FORK : null,
                arrival, null);
    }

    private static TraceEvent trace(long seq, long offset, TraceType type, UUID batch, UUID stage) {
        return new TraceEvent(seq, F + offset, type, batch, id(50), stage, null, BigDecimal.TEN, BigDecimal.ONE, null);
    }

    private static SimulationResult result(List<TraceEvent> trace, List<BatchFinish> finishes) {
        return new SimulationResult("2.0.0", F, F + 20 * H, SimulationResult.TerminationReason.COMPLETED, 0, null,
                List.of(), List.of(), List.of(), null, List.of(), trace, finishes);
    }
}
