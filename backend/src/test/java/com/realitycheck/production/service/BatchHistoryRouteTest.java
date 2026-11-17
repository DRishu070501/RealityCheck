package com.realitycheck.production.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.service.BatchHistory.Event;
import com.realitycheck.production.service.BatchHistory.InvalidHistoryException;
import com.realitycheck.production.service.BatchHistory.Projection;
import com.realitycheck.production.service.BatchHistory.Stop;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A batch following a route (spec 11): it may skip optional steps only, and finishes at the route's last. */
class BatchHistoryRouteTest {

    private static final Instant T0 = Instant.parse("2026-01-05T03:30:00Z");
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID PARK = UUID.randomUUID();
    private static final UUID SAW = UUID.randomUUID();
    private static final UUID POLISH = UUID.randomUUID();
    private static final UUID RECUT = UUID.randomUUID();
    private static final List<UUID> STAGES = List.of(PLAN, PARK, SAW, POLISH, RECUT);
    /** Plan, optionally park, saw, recut: polishing is not on this route. */
    private static final List<Stop> TOPS = List.of(new Stop(PLAN, false), new Stop(PARK, true),
            new Stop(SAW, false), new Stop(RECUT, false));
    private static final BigDecimal STONES = new BigDecimal("10.0000");

    private final List<Event> events = new ArrayList<>();
    private long seq = 1;
    private int hour;

    @Test
    void anOptionalStepCanBeSkippedAndTheBatchFinishesAtTheRoutesLastStep() {
        throughStage(PLAN);
        queued(SAW);
        throughStage(SAW);
        queued(RECUT);
        assertThat(replay().stageIndex()).as("position among all stages").isEqualTo(4);
        throughStage(RECUT);

        Projection projection = replay();

        assertThat(projection.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(projection.stageIndex()).isEqualTo(STAGES.size());
    }

    @Test
    void anOptionalStepCanAlsoBeTaken() {
        throughStage(PLAN);
        queued(PARK);

        Projection projection = replay();

        assertThat(projection.status()).isEqualTo(BatchStatus.QUEUED);
        assertThat(projection.stageId()).isEqualTo(PARK);
        assertThat(projection.stageIndex()).isEqualTo(1);
    }

    @Test
    void untilQueuedAgainTheBatchWaitsForTheRoutesNextStep() {
        throughStage(PLAN);

        assertThat(replay().stageId()).isEqualTo(PARK);
    }

    @Test
    void aRequiredStepCannotBeSkipped() {
        throughStage(PLAN);
        queued(RECUT);

        assertThatThrownBy(this::replay).isInstanceOf(InvalidHistoryException.class)
                .hasMessageContaining("cannot be queued at that stage");
    }

    @Test
    void aStageOffTheRouteCannotBeVisited() {
        throughStage(PLAN);
        queued(SAW);
        throughStage(SAW);
        queued(POLISH);

        assertThatThrownBy(this::replay).isInstanceOf(InvalidHistoryException.class);
    }

    /** Queued (only the first time), started and completed at the stage. */
    private void throughStage(UUID stage) {
        if (events.isEmpty()) {
            queued(stage);
        }
        add(EventType.STAGE_STARTED, stage, null);
        add(EventType.STAGE_COMPLETED, stage, STONES);
    }

    private void queued(UUID stage) {
        add(EventType.STAGE_QUEUED, stage, null);
    }

    private void add(EventType type, UUID stage, BigDecimal stones) {
        events.add(new Event(UUID.randomUUID(), seq++, type, stage, T0.plusSeconds(3600L * hour++), stones,
                stones, null, null, false));
    }

    private Projection replay() {
        return BatchHistory.replay(STAGES, TOPS, STONES, STONES, BatchHistory.effective(events));
    }
}
