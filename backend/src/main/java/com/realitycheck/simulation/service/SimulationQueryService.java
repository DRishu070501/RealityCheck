package com.realitycheck.simulation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.error.ApiException;
import com.realitycheck.scenario.comparison.RunResultsPort;
import com.realitycheck.scenario.service.ScenarioReader;
import com.realitycheck.scenario.service.ScenarioRunLookup;
import com.realitycheck.scenario.snapshot.SnapshotJson;
import com.realitycheck.simulation.api.RunDtos.RunResponse;
import com.realitycheck.simulation.api.RunDtos.RunResultsResponse;
import com.realitycheck.simulation.api.RunDtos.SimulatedEventResponse;
import com.realitycheck.simulation.api.SimulationDtos;
import com.realitycheck.simulation.api.SimulationDtos.BottleneckEvidence;
import com.realitycheck.simulation.api.SimulationDtos.PoolResult;
import com.realitycheck.simulation.api.SimulationDtos.StageResult;
import com.realitycheck.simulation.api.SimulationDtos.Summary;
import com.realitycheck.simulation.domain.RunStatus;
import com.realitycheck.simulation.domain.SimulationRun;
import com.realitycheck.simulation.persistence.SimulationRunRepository;
import com.realitycheck.simulation.service.SimulationResultWriter.StoredSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads stored runs for the API and for other modules. Stored results are served exactly as
 * written, labelled with the algorithm version that produced them, whatever version the code now has.
 */
@Service
@Transactional(readOnly = true)
public class SimulationQueryService implements ScenarioRunLookup, RunResultsPort {

    private final SimulationRunRepository runs;
    private final ScenarioReader scenarioReader;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SimulationQueryService(SimulationRunRepository runs, ScenarioReader scenarioReader, JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.runs = runs;
        this.scenarioReader = scenarioReader;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ---- API ---------------------------------------------------------------------------------

    public RunResponse run(UUID runId) {
        SimulationRun run = find(runId);
        return toResponse(run, scenarioReader.names(List.of(run.getScenarioId())).get(run.getScenarioId()));
    }

    public PageResponse<RunResponse> runsOfScenario(UUID scenarioId, Pageable pageable) {
        String name = scenarioReader.find(scenarioId)
                .orElseThrow(() -> ApiException.notFound("Scenario", scenarioId)).name();
        return PageResponse.from(runs.findByScenarioId(scenarioId, pageable), run -> toResponse(run, name));
    }

    public RunResultsResponse results(UUID runId) {
        SimulationRun run = completed(runId);
        StoredSummary stored = summary(run);
        return new RunResultsResponse(run.getId(), run.getScenarioId(), SimulationDtos.SIMULATED,
                run.getAlgorithmVersion(), stored.currencyCode(), run.getForkAt(), run.getSimulatedEndAt(),
                run.getTerminationReason(), run.getEventsProcessed(), stored.summary(), stored.bottleneck(),
                stored.orders(), poolRows(runId), stored.unfinished());
    }

    public List<StageResult> stages(UUID runId) {
        completed(runId);
        return stageRows(runId);
    }

    public List<PoolResult> resources(UUID runId) {
        completed(runId);
        return poolRows(runId);
    }

    public PageResponse<SimulatedEventResponse> events(UUID runId, UUID stageIdOrNull, UUID orderIdOrNull,
            Pageable pageable) {
        completed(runId);
        StringBuilder where = new StringBuilder(" where run_id = ?");
        List<Object> args = new ArrayList<>(List.of(runId));
        if (stageIdOrNull != null) {
            where.append(" and stage_id = ?");
            args.add(stageIdOrNull);
        }
        if (orderIdOrNull != null) {
            where.append(" and order_id = ?");
            args.add(orderIdOrNull);
        }
        Long total = jdbc.queryForObject("select count(*) from simulation_event_result" + where, Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<SimulatedEventResponse> items = jdbc.query("""
                select seq, occurred_at, event_type, batch_id, order_id, stage_id, resource_id, stone_quantity,
                       carat_quantity, capacity
                from simulation_event_result""" + where + " order by seq limit ? offset ?",
                (rs, i) -> new SimulatedEventResponse(rs.getLong("seq"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("event_type"),
                        rs.getObject("batch_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getObject("stage_id", UUID.class), rs.getObject("resource_id", UUID.class),
                        rs.getBigDecimal("stone_quantity"), rs.getBigDecimal("carat_quantity"),
                        (Integer) rs.getObject("capacity")),
                pageArgs.toArray());
        return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(), total == null ? 0 : total);
    }

    // ---- ScenarioRunLookup -------------------------------------------------------------------

    @Override
    public boolean hasRuns(UUID scenarioId) {
        return runs.existsByScenarioId(scenarioId);
    }

    @Override
    public Map<UUID, LatestRun> latestRuns(Collection<UUID> scenarioIds) {
        Map<UUID, LatestRun> latest = new HashMap<>();
        if (scenarioIds.isEmpty()) {
            return latest;
        }
        for (SimulationRun run : runs.findLatestByScenarioIds(scenarioIds)) {
            latest.putIfAbsent(run.getScenarioId(),
                    new LatestRun(run.getId(), run.getStatus().name(), run.getFinishedAt()));
        }
        return latest;
    }

    // ---- RunResultsPort ----------------------------------------------------------------------

    @Override
    public Optional<RunView> load(UUID runId) {
        return runs.findById(runId).map(this::toView);
    }

    @Override
    public Optional<RunView> latestCompleted(UUID scenarioId, String baseStateHash) {
        return runs.findFirstByScenarioIdAndStatusAndBaseStateHashOrderBySubmittedAtDescIdDesc(scenarioId,
                RunStatus.COMPLETED, baseStateHash).map(this::toView);
    }

    @Override
    public Optional<RunView> latestCompleted(UUID scenarioId) {
        return runs.findFirstByScenarioIdAndStatusOrderBySubmittedAtDescIdDesc(scenarioId, RunStatus.COMPLETED)
                .map(this::toView);
    }

    private RunView toView(SimulationRun run) {
        var applied = SnapshotJson.read(run.getInputSnapshot()).appliedChanges();
        if (run.getStatus() != RunStatus.COMPLETED) {
            return new RunView(run.getId(), run.getScenarioId(), run.getFactoryId(), run.getStatus().name(),
                    run.getForkAt(), run.getBaseStateHash(), run.getAlgorithmVersion(), run.getTerminationReason(),
                    null, null, null, List.of(), List.of(), List.of(), applied);
        }
        StoredSummary stored = summary(run);
        Summary s = stored.summary();
        SummaryView summary = new SummaryView(s.elapsedSeconds(), s.processingSeconds(), s.waitingWorkingSeconds(),
                s.throughputStonesPerHour(), s.outputStones(), s.outputCarats(), s.cost().total(),
                s.cost().perOutputStone(), s.cost().employee(), s.cost().machine(), s.cost().fixed(),
                s.machineUtilization(), s.employeeUtilization(), s.ordersCompleted(), s.ordersDelayed(),
                s.onTimeDeliveryRate());
        List<StageView> stages = stageRows(run.getId()).stream()
                .map(st -> new StageView(st.stageId(), st.code(), st.name(), st.sequence(),
                        st.meanWaitWorkingSeconds(), st.meanQueueLength(), st.maxQueueLength(),
                        st.throughputStonesPerHour(), st.bottleneck().limitingPoolUtilization(),
                        st.bottleneck().score()))
                .toList();
        List<PoolView> pools = poolRows(run.getId()).stream()
                .map(p -> new PoolView(p.resourceId(), p.name(), p.kind(), p.capacity(), p.utilization(),
                        p.busyUnitSeconds(), p.idleUnitSeconds(), p.cost()))
                .toList();
        List<OrderView> orders = stored.orders().stream()
                .map(o -> new OrderView(o.orderId(), o.orderNumber(), o.dueAt(), o.completesAt(), o.outcome()))
                .toList();
        return new RunView(run.getId(), run.getScenarioId(), run.getFactoryId(), run.getStatus().name(),
                run.getForkAt(), run.getBaseStateHash(), run.getAlgorithmVersion(), run.getTerminationReason(),
                stored.currencyCode(), summary, stored.bottleneck().stageId(), stages, pools, orders, applied);
    }

    // ---- Rows --------------------------------------------------------------------------------

    private List<StageResult> stageRows(UUID runId) {
        return jdbc.query("select * from simulation_stage_result where run_id = ? order by sequence",
                STAGE_ROW, runId);
    }

    private List<PoolResult> poolRows(UUID runId) {
        return jdbc.query("""
                select * from simulation_resource_result where run_id = ?
                order by case kind when 'MACHINE' then 0 else 1 end, name, resource_id""", POOL_ROW, runId);
    }

    private static final RowMapper<StageResult> STAGE_ROW = (rs, i) -> new StageResult(
            rs.getObject("stage_id", UUID.class), rs.getString("stage_code"), rs.getString("stage_name"),
            rs.getInt("sequence"), rs.getInt("jobs_completed"), rs.getBigDecimal("input_stones"),
            rs.getBigDecimal("output_stones"), rs.getBigDecimal("loss_stones"), rs.getBigDecimal("input_carats"),
            rs.getBigDecimal("output_carats"), rs.getBigDecimal("loss_carats"), rs.getLong("processing_seconds"),
            rs.getLong("waiting_working_seconds"), rs.getLong("waiting_calendar_seconds"),
            rs.getBigDecimal("mean_wait_working_seconds"), rs.getBigDecimal("mean_stage_seconds"),
            rs.getLong("max_stage_seconds"), rs.getBigDecimal("mean_queue_length"), rs.getInt("max_queue_length"),
            rs.getBigDecimal("throughput_stones_per_hour"), rs.getBigDecimal("fixed_cost"), evidence(rs));

    private static BottleneckEvidence evidence(ResultSet rs) throws SQLException {
        return new BottleneckEvidence(rs.getObject("limiting_resource_id", UUID.class),
                rs.getString("limiting_resource_name"), rs.getBigDecimal("limiting_utilization"),
                rs.getBigDecimal("utilization_signal"), rs.getBigDecimal("wait_signal"),
                rs.getBigDecimal("queue_signal"), rs.getBigDecimal("saturation_signal"),
                rs.getBigDecimal("blocked_share"), rs.getBigDecimal("bottleneck_score"));
    }

    private static final RowMapper<PoolResult> POOL_ROW = (rs, i) -> new PoolResult(
            rs.getObject("resource_id", UUID.class), rs.getString("name"), rs.getString("kind"), rs.getInt("capacity"),
            rs.getLong("busy_unit_seconds"), rs.getLong("available_unit_seconds"), rs.getLong("idle_unit_seconds"),
            rs.getBigDecimal("utilization"), rs.getString("utilization_null_reason"), rs.getBoolean("overcommitted"),
            rs.getBigDecimal("cost"));

    // ---- Helpers -----------------------------------------------------------------------------

    /** Newest first. */
    public static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id"));

    private SimulationRun find(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> ApiException.notFound("Simulation run", runId));
    }

    private SimulationRun completed(UUID runId) {
        SimulationRun run = find(runId);
        if (run.getStatus() != RunStatus.COMPLETED) {
            throw ApiException.conflict("Run " + runId + " is " + run.getStatus()
                    + "; results are available once it is COMPLETED")
                    .withProperty("status", run.getStatus().name());
        }
        return run;
    }

    private StoredSummary summary(SimulationRun run) {
        try {
            return objectMapper.readValue(run.getSummary(), StoredSummary.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored summary of run " + run.getId() + " could not be read", ex);
        }
    }

    private static RunResponse toResponse(SimulationRun run, String scenarioName) {
        return new RunResponse(run.getId(), run.getScenarioId(), scenarioName, run.getFactoryId(),
                run.getStatus().name(), run.getForkAt(), run.getAlgorithmVersion(), run.getInputHash(),
                run.getBaseStateHash(), run.getTerminationReason(), run.getSimulatedEndAt(), run.getEventsProcessed(),
                run.getFailureMessage(), run.getSubmittedAt(), run.getStartedAt(), run.getFinishedAt());
    }
}
