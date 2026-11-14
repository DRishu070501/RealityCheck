package com.realitycheck.production.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.service.BatchHistory.Event;
import com.realitycheck.production.service.BatchHistory.InvalidHistoryException;
import com.realitycheck.production.service.BatchHistory.Projection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BatchHistoryTest {

    private static final Instant T0 = Instant.parse("2026-01-05T03:30:00Z");
    private static final UUID CUTTING = UUID.randomUUID();
    private static final UUID POLISHING = UUID.randomUUID();
    private static final List<UUID> STAGES = List.of(CUTTING, POLISHING);
    private static final BigDecimal STONES = new BigDecimal("100.0000");
    private static final BigDecimal CARATS = new BigDecimal("130.0000");

    private final List<Event> events = new ArrayList<>();
    private long seq = 1;

    @Test
    void aBatchWithNoEventsHasNoStatus() {
        Projection projection = replay();

        assertThat(projection.status()).isNull();
        assertThat(projection.stageId()).isNull();
    }

    @Test
    void aQueuedBatchWaitsAtTheFirstStageHoldingItsInitialQuantity() {
        step(EventType.STAGE_QUEUED, CUTTING, 0);

        Projection projection = replay();

        assertThat(projection.status()).isEqualTo(BatchStatus.QUEUED);
        assertThat(projection.stageId()).isEqualTo(CUTTING);
        assertThat(projection.stageIndex()).isZero();
        assertThat(projection.stones()).isEqualByComparingTo("100");
        assertThat(projection.queuedAt()).isEqualTo(at(0));
        assertThat(projection.startedAt()).isNull();
    }

    @Test
    void startingMovesTheBatchIntoProgress() {
        step(EventType.STAGE_QUEUED, CUTTING, 0);
        step(EventType.STAGE_STARTED, CUTTING, 1);

        Projection projection = replay();

        assertThat(projection.status()).isEqualTo(BatchStatus.IN_PROGRESS);
        assertThat(projection.startedAt()).isEqualTo(at(1));
    }

    @Test
    void completingAStageCarriesItsOutputToTheNextQueue() {
        walkThroughCutting("98", "80.6");

        Projection projection = replay();

        assertThat(projection.status()).isEqualTo(BatchStatus.QUEUED);
        assertThat(projection.stageId()).isEqualTo(POLISHING);
        assertThat(projection.stones()).isEqualByComparingTo("98");
        assertThat(projection.carats()).isEqualByComparingTo("80.6");
        assertThat(projection.queuedAt()).isEqualTo(at(5));
    }

    @Test
    void aCompletionWithoutItsFollowOnQueueEventStillCountsAsQueuedNext() {
        step(EventType.STAGE_QUEUED, CUTTING, 0);
        step(EventType.STAGE_STARTED, CUTTING, 1);
        completed(CUTTING, 5, "98", "80.6");

        Projection projection = replay();

        assertThat(projection.status()).isEqualTo(BatchStatus.QUEUED);
        assertThat(projection.stageId()).isEqualTo(POLISHING);
        assertThat(projection.stageIndex()).isEqualTo(1);
    }

    @Test
    void completingTheLastStageFinishesTheBatch() {
        walkThroughCutting("98", "80.6");
        step(EventType.STAGE_STARTED, POLISHING, 6);
        completed(POLISHING, 20, "97", "66.1");

        Projection projection = replay();

        assertThat(projection.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(projection.stageId()).isNull();
        assertThat(projection.stageIndex()).isEqualTo(2);
        assertThat(projection.completedAt()).isEqualTo(at(20));
        assertThat(projection.stones()).isEqualByComparingTo("97");
    }

    @Test
    void aBatchCannotStartTwice() {
        step(EventType.STAGE_QUEUED, CUTTING, 0);
        step(EventType.STAGE_STARTED, CUTTING, 1);
        step(EventType.STAGE_STARTED, CUTTING, 2);

        assertInvalid("already in progress");
    }

    @Test
    void aBatchCannotCompleteAStageItHasNotStarted() {
        step(EventType.STAGE_QUEUED, CUTTING, 0);
        completed(CUTTING, 1, "98", "80");

        assertInvalid("not in progress");
    }

    @Test
    void aBatchCannotSkipAStage() {
        step(EventType.STAGE_QUEUED, CUTTING, 0);
        step(EventType.STAGE_STARTED, POLISHING, 1);

        assertInvalid("not queued at that stage");
    }

    @Test
    void aStageCannotBeProcessedTwice() {
        walkThroughCutting("98", "80.6");
        step(EventType.STAGE_STARTED, CUTTING, 6);

        assertInvalid("not queued at that stage");
    }

    @Test
    void nothingCanHappenToAFinishedBatch() {
        walkThroughCutting("98", "80.6");
        step(EventType.STAGE_STARTED, POLISHING, 6);
        completed(POLISHING, 20, "97", "66.1");
        step(EventType.STAGE_STARTED, POLISHING, 21);

        assertInvalid("already finished");
    }

    @Test
    void aStageCannotProduceMoreThanEnteredIt() {
        step(EventType.STAGE_QUEUED, CUTTING, 0);
        step(EventType.STAGE_STARTED, CUTTING, 1);
        completed(CUTTING, 5, "101", "80");
        assertInvalid("Output stones (101.0000) cannot exceed the 100.0000");

        events.remove(events.size() - 1);
        completed(CUTTING, 5, "98", "130.0001");
        assertInvalid("Output carats");
    }

    @Test
    void aCompletionMustRecordBothQuantities() {
        step(EventType.STAGE_QUEUED, CUTTING, 0);
        step(EventType.STAGE_STARTED, CUTTING, 1);
        events.add(new Event(UUID.randomUUID(), seq++, EventType.STAGE_COMPLETED, CUTTING, at(5),
                new BigDecimal("98"), null, null, null, false));

        assertInvalid("must record its output quantities");
    }

    @Test
    void aBatchWithNoWorkflowCannotBeReplayed() {
        assertThatThrownBy(() -> BatchHistory.replay(List.of(), STONES, CARATS, List.of()))
                .isInstanceOf(InvalidHistoryException.class)
                .hasMessageContaining("no active workflow");
    }

    @Test
    void aQuantityCorrectionReplacesTheOutputWithoutTouchingTheOriginal() {
        walkThroughCutting("99", "80.6");
        Event completion = events.get(2);
        correct(completion, "98", null, false);

        List<Event> effective = BatchHistory.effective(events);
        Projection projection = replay();

        assertThat(projection.stones()).isEqualByComparingTo("98");
        assertThat(projection.carats()).isEqualByComparingTo("80.6");
        assertThat(effective).hasSize(4).noneMatch(event -> event.type() == EventType.CORRECTION);
        assertThat(completion.stones()).isEqualByComparingTo("99");
    }

    @Test
    void theLatestCorrectionWins() {
        walkThroughCutting("99", "80.6");
        Event completion = events.get(2);
        correct(completion, "98", null, false);
        correct(completion, "97", "80.1", false);

        Projection projection = replay();

        assertThat(projection.stones()).isEqualByComparingTo("97");
        assertThat(projection.carats()).isEqualByComparingTo("80.1");
    }

    @Test
    void aCorrectionThatMakesLaterWorkImpossibleIsRejected() {
        walkThroughCutting("98", "80.6");
        step(EventType.STAGE_STARTED, POLISHING, 6);
        completed(POLISHING, 20, "97", "66.1");
        correct(events.get(2), "90", null, false);

        assertInvalid("Output stones (97.0000) cannot exceed the 90.0000");
    }

    @Test
    void voidingAStartPutsTheBatchBackInTheQueue() {
        step(EventType.STAGE_QUEUED, CUTTING, 0);
        Event start = step(EventType.STAGE_STARTED, CUTTING, 1);
        correct(start, null, null, true);

        Projection projection = replay();

        assertThat(projection.status()).isEqualTo(BatchStatus.QUEUED);
        assertThat(projection.stageId()).isEqualTo(CUTTING);
        assertThat(projection.startedAt()).isNull();
    }

    @Test
    void voidingACompletionAndItsFollowOnReturnsTheBatchToInProgress() {
        walkThroughCutting("98", "80.6");
        correct(events.get(2), null, null, true);
        correct(events.get(3), null, null, true);

        Projection projection = replay();

        assertThat(projection.status()).isEqualTo(BatchStatus.IN_PROGRESS);
        assertThat(projection.stageId()).isEqualTo(CUTTING);
        assertThat(projection.stones()).isEqualByComparingTo("100");
    }

    @Test
    void voidingAStepThatLaterWorkDependsOnIsRejected() {
        walkThroughCutting("98", "80.6");
        correct(events.get(1), null, null, true);

        assertInvalid("not in progress");
    }

    @Test
    void effectiveEventsAreOrderedByWhenTheyHappenedThenByRecordingOrder() {
        Event later = new Event(UUID.randomUUID(), 1, EventType.STAGE_STARTED, CUTTING, at(1), null, null, null,
                null, false);
        Event earlier = new Event(UUID.randomUUID(), 2, EventType.STAGE_QUEUED, CUTTING, at(0), null, null, null,
                null, false);
        Event sameTime = new Event(UUID.randomUUID(), 3, EventType.STAGE_COMPLETED, CUTTING, at(1), STONES, CARATS,
                null, null, false);

        assertThat(BatchHistory.effective(List.of(later, earlier, sameTime)))
                .extracting(Event::seq).containsExactly(2L, 1L, 3L);
    }

    private void walkThroughCutting(String stonesOut, String caratsOut) {
        step(EventType.STAGE_QUEUED, CUTTING, 0);
        step(EventType.STAGE_STARTED, CUTTING, 1);
        completed(CUTTING, 5, stonesOut, caratsOut);
        step(EventType.STAGE_QUEUED, POLISHING, 5);
    }

    private Event step(EventType type, UUID stage, int hour) {
        Event event = new Event(UUID.randomUUID(), seq++, type, stage, at(hour), null, null, null, null, false);
        events.add(event);
        return event;
    }

    private void completed(UUID stage, int hour, String stones, String carats) {
        events.add(new Event(UUID.randomUUID(), seq++, EventType.STAGE_COMPLETED, stage, at(hour),
                new BigDecimal(stones).setScale(4), new BigDecimal(carats).setScale(4), 3600L, null, false));
    }

    private void correct(Event target, String stones, String carats, boolean voids) {
        events.add(new Event(UUID.randomUUID(), seq++, EventType.CORRECTION, target.stageId(), at(30),
                stones == null ? null : new BigDecimal(stones).setScale(4),
                carats == null ? null : new BigDecimal(carats).setScale(4), null, target.id(), voids));
    }

    private Projection replay() {
        return BatchHistory.replay(STAGES, STONES, CARATS, BatchHistory.effective(events));
    }

    private void assertInvalid(String messagePart) {
        assertThatThrownBy(this::replay)
                .isInstanceOf(InvalidHistoryException.class)
                .hasMessageContaining(messagePart);
    }

    private static Instant at(int hour) {
        return T0.plusSeconds(hour * 3600L);
    }
}
