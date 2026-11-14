package com.realitycheck.simulation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.simulation.api.SimulationDtos.Bottleneck;
import com.realitycheck.simulation.api.SimulationDtos.OrderResult;
import com.realitycheck.simulation.api.SimulationDtos.PoolResult;
import com.realitycheck.simulation.api.SimulationDtos.SimulationResultResponse;
import com.realitycheck.simulation.api.SimulationDtos.StageResult;
import com.realitycheck.simulation.api.SimulationDtos.Summary;
import com.realitycheck.simulation.api.SimulationDtos.Unfinished;
import com.realitycheck.simulation.engine.SimulationResult.TraceEvent;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a finished run: stage, resource and event rows and the summary, and marks the run
 * COMPLETED, all in one transaction. If anything fails, no result row is kept.
 */
@Service
public class SimulationResultWriter {

    /** The summary column's content: everything in the result that is not a stage or pool row. */
    public record StoredSummary(String currencyCode, Summary summary, List<OrderResult> orders, Bottleneck bottleneck,
            List<Unfinished> unfinished) {
    }

    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SimulationResultWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @throws IllegalStateException if the run is no longer RUNNING (for example it was marked
     *                               failed by recovery); everything written is rolled back
     */
    @Transactional
    public void write(UUID runId, SimulationResultResponse response, List<TraceEvent> trace) {
        Instant now = clock.instant();
        Timestamp created = Timestamp.from(now);
        UUID primary = response.bottleneck().stageId();

        List<StageResult> stages = response.stages();
        jdbc.batchUpdate("""
                insert into simulation_stage_result (id, run_id, stage_id, stage_code, stage_name, sequence,
                  jobs_completed, input_stones, output_stones, loss_stones, input_carats, output_carats, loss_carats,
                  processing_seconds, waiting_working_seconds, waiting_calendar_seconds, mean_wait_working_seconds,
                  mean_stage_seconds, max_stage_seconds, mean_queue_length, max_queue_length,
                  throughput_stones_per_hour, fixed_cost, limiting_resource_id, limiting_resource_name,
                  limiting_utilization, utilization_signal, wait_signal, queue_signal, saturation_signal,
                  blocked_share, bottleneck_score, primary_bottleneck, created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                stages, BATCH, (ps, s) -> {
                    int i = 1;
                    ps.setObject(i++, UUID.randomUUID());
                    ps.setObject(i++, runId);
                    ps.setObject(i++, s.stageId());
                    ps.setString(i++, s.code());
                    ps.setString(i++, s.name());
                    ps.setInt(i++, s.sequence());
                    ps.setInt(i++, s.jobsCompleted());
                    ps.setBigDecimal(i++, s.inputStones());
                    ps.setBigDecimal(i++, s.outputStones());
                    ps.setBigDecimal(i++, s.lossStones());
                    ps.setBigDecimal(i++, s.inputCarats());
                    ps.setBigDecimal(i++, s.outputCarats());
                    ps.setBigDecimal(i++, s.lossCarats());
                    ps.setLong(i++, s.processingSeconds());
                    ps.setLong(i++, s.waitingWorkingSeconds());
                    ps.setLong(i++, s.waitingCalendarSeconds());
                    ps.setBigDecimal(i++, s.meanWaitWorkingSeconds());
                    ps.setBigDecimal(i++, s.meanStageSeconds());
                    ps.setLong(i++, s.maxStageSeconds());
                    ps.setBigDecimal(i++, s.meanQueueLength());
                    ps.setInt(i++, s.maxQueueLength());
                    ps.setBigDecimal(i++, s.throughputStonesPerHour());
                    ps.setBigDecimal(i++, s.fixedCost());
                    ps.setObject(i++, s.bottleneck().limitingPoolId());
                    ps.setString(i++, s.bottleneck().limitingPoolName());
                    ps.setBigDecimal(i++, s.bottleneck().limitingPoolUtilization());
                    ps.setBigDecimal(i++, s.bottleneck().utilizationSignal());
                    ps.setBigDecimal(i++, s.bottleneck().waitSignal());
                    ps.setBigDecimal(i++, s.bottleneck().queueSignal());
                    ps.setBigDecimal(i++, s.bottleneck().saturation());
                    ps.setBigDecimal(i++, s.bottleneck().blockedShare());
                    ps.setBigDecimal(i++, s.bottleneck().score());
                    ps.setBoolean(i++, Objects.equals(primary, s.stageId()));
                    ps.setTimestamp(i, created);
                });

        List<PoolResult> pools = response.pools();
        jdbc.batchUpdate("""
                insert into simulation_resource_result (id, run_id, resource_id, kind, name, capacity,
                  busy_unit_seconds, available_unit_seconds, idle_unit_seconds, utilization,
                  utilization_null_reason, overcommitted, cost, created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                pools, BATCH, (ps, p) -> {
                    int i = 1;
                    ps.setObject(i++, UUID.randomUUID());
                    ps.setObject(i++, runId);
                    ps.setObject(i++, p.resourceId());
                    ps.setString(i++, p.kind());
                    ps.setString(i++, p.name());
                    ps.setInt(i++, p.capacity());
                    ps.setLong(i++, p.busyUnitSeconds());
                    ps.setLong(i++, p.availableUnitSeconds());
                    ps.setLong(i++, p.idleUnitSeconds());
                    ps.setBigDecimal(i++, p.utilization());
                    ps.setString(i++, p.utilizationNullReason());
                    ps.setBoolean(i++, p.overcommitted());
                    ps.setBigDecimal(i++, p.cost());
                    ps.setTimestamp(i, created);
                });

        jdbc.batchUpdate("""
                insert into simulation_event_result (id, run_id, seq, occurred_at, event_type, batch_id, order_id,
                  stage_id, resource_id, stone_quantity, carat_quantity, capacity)
                values (?,?,?,?,?,?,?,?,?,?,?,?)""",
                trace, BATCH, (ps, e) -> {
                    int i = 1;
                    ps.setObject(i++, UUID.randomUUID());
                    ps.setObject(i++, runId);
                    ps.setLong(i++, e.seq());
                    ps.setTimestamp(i++, Timestamp.from(Instant.ofEpochSecond(e.time())));
                    ps.setString(i++, e.type().name());
                    ps.setObject(i++, e.batchId());
                    ps.setObject(i++, e.orderId());
                    ps.setObject(i++, e.stageId());
                    ps.setObject(i++, e.poolId());
                    ps.setBigDecimal(i++, e.stones());
                    ps.setBigDecimal(i++, e.carats());
                    if (e.capacity() == null) {
                        ps.setNull(i, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(i, e.capacity());
                    }
                });

        StoredSummary stored = new StoredSummary(response.summary().cost().currencyCode(), response.summary(),
                response.orders(), response.bottleneck(), response.unfinished());
        int updated = jdbc.update("""
                update simulation_run
                set status = 'COMPLETED', termination_reason = ?, simulated_end_at = ?, events_processed = ?,
                    summary = ?::jsonb, finished_at = ?
                where id = ? and status = 'RUNNING'""",
                response.terminationReason(), Timestamp.from(response.endsAt()), response.eventsProcessed(),
                json(stored), created, runId);
        if (updated != 1) {
            throw new IllegalStateException("Run " + runId + " is no longer running; results discarded");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Summary could not be serialised", ex);
        }
    }
}
