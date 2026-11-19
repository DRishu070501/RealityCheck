package com.realitycheck.simulation.backtest;

import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.scenario.comparison.RecordedProgressService;
import com.realitycheck.scenario.service.SnapshotAssembler;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BatchSnapshot;
import com.realitycheck.simulation.backtest.BacktestComparison.ForkComparison;
import com.realitycheck.simulation.engine.InvalidSimulationInputException;
import com.realitycheck.simulation.engine.SimulationEngine;
import com.realitycheck.simulation.engine.SimulationResult;
import com.realitycheck.simulation.service.SnapshotInputAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks the engine against the past: for each fork date, simulates the factory as recorded then with no
 * changes (arrivals after the fork included, as they were recorded) and compares the simulation with what
 * recorded history shows over [fork, min(simulated end, last recorded event)]. The engine is deterministic,
 * so a backtest is computed when requested and stored as it was computed.
 */
@Service
public class BacktestService {

    /** Enough to cover a month weekly with room to spare, while keeping a request within seconds. */
    static final int MAX_FORKS = 8;
    static final int MAX_HORIZON_DAYS = 90;
    static final int DEFAULT_FORKS = 4;

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final SnapshotAssembler assembler;
    private final RecordedProgressService recorded;
    private final BacktestRepository backtests;
    private final BacktestForkRepository forks;
    private final Clock clock;
    private final SimulationEngine engine = new SimulationEngine();

    public BacktestService(SnapshotAssembler assembler, RecordedProgressService recorded,
            BacktestRepository backtests, BacktestForkRepository forks, Clock clock) {
        this.assembler = assembler;
        this.recorded = recorded;
        this.backtests = backtests;
        this.forks = forks;
        this.clock = clock;
    }

    public record StoredBacktest(Backtest backtest, List<ForkComparison> forks) {
    }

    /**
     * @param forkAts the fork dates; when empty, {@value #DEFAULT_FORKS} dates spread evenly over the recorded
     *                history, each at the start of a day in the factory's time zone
     */
    @Transactional
    public StoredBacktest run(UUID factoryId, List<Instant> forkAts) {
        return run(factoryId, forkAts, null);
    }

    /**
     * @param horizonDays when set, each comparison stops this many days after its date at the latest (1 to
     *                    {@value #MAX_HORIZON_DAYS}); otherwise at the simulated end or the last recorded event
     */
    @Transactional
    public StoredBacktest run(UUID factoryId, List<Instant> forkAts, Integer horizonDays) {
        if (horizonDays != null && (horizonDays < 1 || horizonDays > MAX_HORIZON_DAYS)) {
            throw new ValidationException("horizonDays", "must be 1 to " + MAX_HORIZON_DAYS);
        }
        Instant first = recorded.firstEventAt(factoryId);
        Instant last = recorded.lastEventAt(factoryId);
        if (first == null) {
            assembler.configuration(factoryId, clock.instant()); // 404 for an unknown factory
            throw ApiException.unprocessable("The factory has no recorded history to test the simulation against.");
        }
        List<Instant> dates = forkAts == null || forkAts.isEmpty() ? defaultForks(factoryId, first, last)
                : validated(forkAts, first, last);
        if (dates.isEmpty()) {
            throw ApiException.unprocessable("The recorded history is too short to choose fork dates in it.");
        }

        Instant now = clock.instant();
        Backtest backtest = backtests.save(new Backtest(UUID.randomUUID(), now, factoryId,
                SimulationEngine.ALGORITHM_VERSION));
        List<ForkComparison> comparisons = new ArrayList<>();
        for (Instant forkAt : dates) {
            ForkComparison comparison = compare(factoryId, forkAt, last, horizonDays);
            forks.save(new BacktestFork(UUID.randomUUID(), now, backtest.getId(), comparisons.size(), comparison));
            comparisons.add(comparison);
        }
        log.info("Backtest backtestId={} factoryId={} forks={}", backtest.getId(), factoryId, dates.size());
        return new StoredBacktest(backtest, List.copyOf(comparisons));
    }

    @Transactional(readOnly = true)
    public StoredBacktest get(UUID backtestId) {
        Backtest backtest = backtests.findById(backtestId)
                .orElseThrow(() -> ApiException.notFound("Backtest", backtestId));
        return stored(backtest);
    }

    @Transactional(readOnly = true)
    public Optional<StoredBacktest> latest(UUID factoryId) {
        return backtests.findFirstByFactoryIdOrderByCreatedAtDescIdDesc(factoryId).map(this::stored);
    }

    private StoredBacktest stored(Backtest backtest) {
        return new StoredBacktest(backtest, forks.findByBacktestIdOrderByOrdinalAsc(backtest.getId()).stream()
                .map(BacktestFork::getResult).toList());
    }

    private ForkComparison compare(UUID factoryId, Instant forkAt, Instant lastEvent, Integer horizonDays) {
        Snapshot snapshot = assembler.build(factoryId, forkAt, true);
        SimulationResult result;
        try {
            result = engine.run(SnapshotInputAdapter.toInput(snapshot));
        } catch (InvalidSimulationInputException ex) {
            throw ApiException.unprocessable("The factory cannot be simulated as configured: " + ex.getMessage());
        }
        Instant simulatedEnd = Instant.ofEpochSecond(result.endTime());
        boolean cut = lastEvent.isBefore(simulatedEnd);
        Instant until = cut ? lastEvent : simulatedEnd;
        if (horizonDays != null && forkAt.plus(Duration.ofDays(horizonDays)).isBefore(until)) {
            until = forkAt.plus(Duration.ofDays(horizonDays));
            cut = false;
        }
        Set<UUID> ids = snapshot.batches().stream().map(BatchSnapshot::id).collect(Collectors.toSet());
        return BacktestComparison.compare(snapshot, result, until, cut, recorded.at(factoryId, until, ids));
    }

    private List<Instant> defaultForks(UUID factoryId, Instant first, Instant last) {
        ZoneId zone = ZoneId.of(assembler.configuration(factoryId, last).timeZone());
        Duration span = Duration.between(first, last);
        TreeSet<Instant> dates = new TreeSet<>();
        for (int i = 1; i <= DEFAULT_FORKS; i++) {
            Instant at = first.plus(span.multipliedBy(i).dividedBy(DEFAULT_FORKS + 1));
            Instant startOfDay = at.atZone(zone).truncatedTo(ChronoUnit.DAYS).toInstant();
            if (startOfDay.isAfter(first) && startOfDay.isBefore(last)) {
                dates.add(startOfDay);
            }
        }
        return List.copyOf(dates);
    }

    private static List<Instant> validated(List<Instant> forkAts, Instant first, Instant last) {
        TreeSet<Instant> dates = new TreeSet<>(forkAts);
        if (dates.size() > MAX_FORKS) {
            throw new ValidationException("forkAts", "must have at most " + MAX_FORKS + " dates");
        }
        for (Instant at : dates) {
            if (!at.isAfter(first) || !at.isBefore(last)) {
                throw new ValidationException("forkAts",
                        "every date must lie within the recorded history (after its first event, before its last)");
            }
        }
        return List.copyOf(dates);
    }
}
