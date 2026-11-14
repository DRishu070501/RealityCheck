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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * A valid history is built by walking a batch a number of steps through a workflow, losing a
 * generated number of stones at each completion.
 */
class BatchHistoryProperties {

    private static final Instant T0 = Instant.parse("2026-01-05T03:30:00Z");
    private static final BigDecimal INITIAL_STONES = new BigDecimal("1000.0000");
    private static final BigDecimal INITIAL_CARATS = new BigDecimal("1300.0000");

    @Property
    void everyPrefixOfAValidHistoryIsValidAndTheBatchOnlyMovesForward(
            @ForAll @IntRange(min = 1, max = 8) int stageCount,
            @ForAll @IntRange(min = 0, max = 24) int steps,
            @ForAll @Size(8) List<@IntRange(min = 0, max = 20) Integer> losses) {
        List<UUID> stages = stages(stageCount);
        List<Event> history = walk(stages, steps, losses);

        int previousIndex = -1;
        BigDecimal previousStones = INITIAL_STONES;
        for (int length = 0; length <= history.size(); length++) {
            Projection projection = BatchHistory.replay(stages, INITIAL_STONES, INITIAL_CARATS,
                    BatchHistory.effective(history.subList(0, length)));

            assertThat(projection.stageIndex()).isGreaterThanOrEqualTo(previousIndex);
            assertThat(projection.stones()).isLessThanOrEqualTo(previousStones);
            assertThat(projection.stones()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            previousIndex = projection.stageIndex();
            previousStones = projection.stones();
        }
    }

    @Property
    void replayDoesNotDependOnTheOrderEventsAreSupplied(
            @ForAll @IntRange(min = 1, max = 8) int stageCount,
            @ForAll @IntRange(min = 0, max = 24) int steps,
            @ForAll @Size(8) List<@IntRange(min = 0, max = 20) Integer> losses,
            @ForAll long shuffleSeed) {
        List<UUID> stages = stages(stageCount);
        List<Event> history = walk(stages, steps, losses);
        List<Event> shuffled = new ArrayList<>(history);
        Collections.shuffle(shuffled, new java.util.Random(shuffleSeed));

        assertThat(BatchHistory.replay(stages, INITIAL_STONES, INITIAL_CARATS, BatchHistory.effective(shuffled)))
                .isEqualTo(BatchHistory.replay(stages, INITIAL_STONES, INITIAL_CARATS,
                        BatchHistory.effective(history)));
    }

    @Property
    void aHistoryWithAStartOrCompletionMissingIsRejectedUnlessItWasTheLastStep(
            @ForAll @IntRange(min = 2, max = 8) int stageCount,
            @ForAll @IntRange(min = 3, max = 24) int steps,
            @ForAll @Size(8) List<@IntRange(min = 0, max = 20) Integer> losses,
            @ForAll @IntRange(min = 0, max = 100) int pick) {
        List<UUID> stages = stages(stageCount);
        List<Event> history = walk(stages, steps, losses);
        List<Integer> removable = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            EventType type = history.get(i).type();
            boolean laterStepExists = history.subList(i + 1, history.size()).stream()
                    .anyMatch(event -> event.type() != EventType.STAGE_QUEUED);
            if (type != EventType.STAGE_QUEUED && laterStepExists) {
                removable.add(i);
            }
        }
        if (removable.isEmpty()) {
            return;
        }
        List<Event> broken = new ArrayList<>(history);
        broken.remove((int) removable.get(pick % removable.size()));

        assertThatThrownBy(() -> BatchHistory.replay(stages, INITIAL_STONES, INITIAL_CARATS,
                BatchHistory.effective(broken))).isInstanceOf(InvalidHistoryException.class);
    }

    @Property
    void aFullWalkFinishesTheBatch(@ForAll @IntRange(min = 1, max = 8) int stageCount,
            @ForAll @Size(8) List<@IntRange(min = 0, max = 20) Integer> losses) {
        List<UUID> stages = stages(stageCount);

        Projection projection = BatchHistory.replay(stages, INITIAL_STONES, INITIAL_CARATS,
                BatchHistory.effective(walk(stages, 2 * stageCount, losses)));

        assertThat(projection.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(projection.stageId()).isNull();
        assertThat(projection.completedAt()).isNotNull();
    }

    private static List<UUID> stages(int count) {
        List<UUID> stages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            stages.add(new UUID(0, i + 1));
        }
        return stages;
    }

    /**
     * Queues the batch, then alternates start and completion for {@code steps} steps or until the
     * workflow ends, adding the follow-on queue event after each completion that is not the last.
     */
    private static List<Event> walk(List<UUID> stages, int steps, List<Integer> losses) {
        List<Event> history = new ArrayList<>();
        long seq = 1;
        int hour = 0;
        BigDecimal stones = INITIAL_STONES;
        BigDecimal carats = INITIAL_CARATS;
        history.add(event(seq++, EventType.STAGE_QUEUED, stages.get(0), hour, null, null));
        int stage = 0;
        boolean inProgress = false;
        for (int step = 0; step < steps && stage < stages.size(); step++) {
            hour++;
            if (!inProgress) {
                history.add(event(seq++, EventType.STAGE_STARTED, stages.get(stage), hour, null, null));
                inProgress = true;
            } else {
                stones = stones.subtract(BigDecimal.valueOf(losses.get(stage))).max(BigDecimal.ZERO);
                carats = carats.multiply(new BigDecimal("0.9")).setScale(4, java.math.RoundingMode.HALF_EVEN);
                history.add(event(seq++, EventType.STAGE_COMPLETED, stages.get(stage), hour, stones, carats));
                inProgress = false;
                stage++;
                if (stage < stages.size()) {
                    history.add(event(seq++, EventType.STAGE_QUEUED, stages.get(stage), hour, null, null));
                }
            }
        }
        return history;
    }

    private static Event event(long seq, EventType type, UUID stage, int hour, BigDecimal stones,
            BigDecimal carats) {
        return new Event(new UUID(1, seq), seq, type, stage, T0.plusSeconds(hour * 3600L), stones, carats, null,
                null, false);
    }
}
