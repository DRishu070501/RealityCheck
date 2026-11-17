package com.realitycheck.production.service;

import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.domain.EventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The rules of a batch's history, as pure functions. {@link #effective} applies corrections;
 * {@link #replay} walks the result through the state machine and either returns where the batch
 * stands or says why the history is impossible.
 *
 * <p>The same code validates a new event before it is stored, maintains the stored projection,
 * and reconstructs the state at a past instant, so those three can never disagree.
 *
 * <pre>
 * (nothing)        --STAGE_QUEUED at first stage-->  QUEUED there
 * QUEUED at s      --STAGE_STARTED at s----------->  IN_PROGRESS at s
 * IN_PROGRESS at s --STAGE_COMPLETED at s--------->  COMPLETED if s is last,
 *                                                    else waiting for STAGE_QUEUED at s+1
 * </pre>
 */
public final class BatchHistory {

    private BatchHistory() {
    }

    /** One stored event of a batch, including corrections that target the batch's events. */
    public record Event(UUID id, long seq, EventType type, UUID stageId, Instant occurredAt, BigDecimal stones,
            BigDecimal carats, Long durationSeconds, UUID correctsEventId, boolean voids) {
    }

    /**
     * Where a batch stands.
     *
     * @param status     null when the batch has no events yet
     * @param stageId    the stage it is queued or in progress at; null when completed or not yet queued
     * @param stageIndex zero-based position of that stage; the stage count when completed
     * @param stones     what the batch currently holds: the output of its last completed stage, or
     *                   its initial quantity
     * @param queuedAt   when it entered the current stage's queue
     * @param startedAt  when work began at the current stage; null unless in progress
     */
    public record Projection(BatchStatus status, UUID stageId, int stageIndex, BigDecimal stones,
            BigDecimal carats, Instant queuedAt, Instant startedAt, Instant completedAt, Instant lastEventAt) {
    }

    /** The events do not describe a possible history. The message is safe to show to a user. */
    public static final class InvalidHistoryException extends RuntimeException {

        public InvalidHistoryException(String message) {
            super(message);
        }
    }

    /**
     * Applies corrections. A voided event disappears; otherwise each correction, in recording
     * order, replaces the quantities and duration it carries. Correction rows themselves are
     * dropped. The result is ordered by when things happened, then by recording order.
     */
    public static List<Event> effective(List<Event> raw) {
        Map<UUID, List<Event>> correctionsByTarget = new HashMap<>();
        for (Event event : raw) {
            if (event.type() == EventType.CORRECTION) {
                correctionsByTarget.computeIfAbsent(event.correctsEventId(), id -> new ArrayList<>()).add(event);
            }
        }
        List<Event> effective = new ArrayList<>();
        for (Event event : raw) {
            if (event.type() == EventType.CORRECTION) {
                continue;
            }
            List<Event> corrections = correctionsByTarget.getOrDefault(event.id(), List.of()).stream()
                    .sorted(Comparator.comparingLong(Event::seq))
                    .toList();
            if (corrections.stream().anyMatch(Event::voids)) {
                continue;
            }
            Event current = event;
            for (Event correction : corrections) {
                current = new Event(current.id(), current.seq(), current.type(), current.stageId(),
                        current.occurredAt(),
                        correction.stones() != null ? correction.stones() : current.stones(),
                        correction.carats() != null ? correction.carats() : current.carats(),
                        correction.durationSeconds() != null ? correction.durationSeconds()
                                : current.durationSeconds(),
                        null, false);
            }
            effective.add(current);
        }
        effective.sort(Comparator.comparing(Event::occurredAt).thenComparingLong(Event::seq));
        return effective;
    }

    /**
     * Walks a batch's effective step events through the state machine.
     *
     * @param stageIds  the workflow's stages in sequence order
     * @param effective output of {@link #effective}; events that are not batch steps are ignored
     * @throws InvalidHistoryException if a step is out of place, out of time order, or produces
     *                                 more than went in
     */
    public static Projection replay(List<UUID> stageIds, BigDecimal initialStones, BigDecimal initialCarats,
            List<Event> effective) {
        return replay(stageIds, linear(stageIds), initialStones, initialCarats, effective);
    }

    /** One step of a batch's path through the workflow; an optional step may be skipped (spec 11). */
    public record Stop(UUID stageId, boolean optional) {
    }

    /** Every stage in sequence, none optional: the path of a batch without a route. */
    public static List<Stop> linear(List<UUID> stageIds) {
        return stageIds.stream().map(id -> new Stop(id, false)).toList();
    }

    /**
     * As {@link #replay(List, BigDecimal, BigDecimal, List)} for a batch following {@code path}: after a
     * stage it is queued at a later step of its path, skipping only optional steps, and it finishes at the
     * last step. The projection's {@code stageIndex} is still the stage's position in {@code stageIds}.
     */
    public static Projection replay(List<UUID> stageIds, List<Stop> path, BigDecimal initialStones,
            BigDecimal initialCarats, List<Event> effective) {
        if (stageIds.isEmpty() || path.isEmpty()) {
            throw new InvalidHistoryException("The factory has no active workflow");
        }
        BatchStatus status = null;
        int index = -1;
        boolean awaitingNextQueue = false;
        BigDecimal stones = initialStones;
        BigDecimal carats = initialCarats;
        Instant queuedAt = null;
        Instant startedAt = null;
        Instant completedAt = null;
        Instant last = null;

        for (Event event : effective) {
            if (!event.type().isBatchStep()) {
                continue;
            }
            if (last != null && event.occurredAt().isBefore(last)) {
                throw new InvalidHistoryException(
                        "An event cannot occur before the batch's previous event at " + last);
            }
            if (status == BatchStatus.COMPLETED) {
                throw new InvalidHistoryException("The batch has already finished the last stage");
            }
            switch (event.type()) {
                case STAGE_QUEUED -> {
                    int expected = status == null ? 0 : nextStop(path, index, event.stageId());
                    if ((status != null && !awaitingNextQueue) || expected < 0
                            || !path.get(expected).stageId().equals(event.stageId())) {
                        throw new InvalidHistoryException("The batch cannot be queued at that stage now");
                    }
                    status = BatchStatus.QUEUED;
                    index = expected;
                    awaitingNextQueue = false;
                    queuedAt = event.occurredAt();
                    startedAt = null;
                }
                case STAGE_STARTED -> {
                    if (status != BatchStatus.QUEUED || awaitingNextQueue
                            || !path.get(index).stageId().equals(event.stageId())) {
                        throw new InvalidHistoryException(status == BatchStatus.IN_PROGRESS
                                ? "The batch is already in progress at its current stage"
                                : "The batch is not queued at that stage");
                    }
                    status = BatchStatus.IN_PROGRESS;
                    startedAt = event.occurredAt();
                }
                case STAGE_COMPLETED -> {
                    if (status != BatchStatus.IN_PROGRESS || !path.get(index).stageId().equals(event.stageId())) {
                        throw new InvalidHistoryException("The batch is not in progress at that stage");
                    }
                    if (event.stones() == null || event.carats() == null) {
                        throw new InvalidHistoryException("A completed stage must record its output quantities");
                    }
                    if (event.stones().compareTo(stones) > 0) {
                        throw new InvalidHistoryException("Output stones (" + event.stones().toPlainString()
                                + ") cannot exceed the " + stones.toPlainString() + " that entered the stage");
                    }
                    if (event.carats().compareTo(carats) > 0) {
                        throw new InvalidHistoryException("Output carats (" + event.carats().toPlainString()
                                + ") cannot exceed the " + carats.toPlainString() + " that entered the stage");
                    }
                    stones = event.stones();
                    carats = event.carats();
                    startedAt = null;
                    if (index == path.size() - 1) {
                        status = BatchStatus.COMPLETED;
                        index = path.size();
                        completedAt = event.occurredAt();
                        queuedAt = null;
                    } else {
                        // Until the follow-on STAGE_QUEUED arrives the batch is, in effect, queued next.
                        status = BatchStatus.QUEUED;
                        awaitingNextQueue = true;
                        queuedAt = event.occurredAt();
                    }
                }
                default -> throw new IllegalStateException("Not a batch step: " + event.type());
            }
            last = event.occurredAt();
        }

        if (awaitingNextQueue) {
            index = index + 1;
        }
        UUID stageId = status == null || status == BatchStatus.COMPLETED ? null : path.get(index).stageId();
        int stageIndex = status == BatchStatus.COMPLETED ? stageIds.size()
                : stageId == null ? -1 : stageIds.indexOf(stageId);
        return new Projection(status, stageId, stageIndex, stones, carats, queuedAt, startedAt, completedAt, last);
    }

    /**
     * The position on the path after {@code from} at which the batch may next be queued at {@code stageId}:
     * the next step, or a later one when only optional steps lie between. -1 when it may not.
     */
    private static int nextStop(List<Stop> path, int from, UUID stageId) {
        for (int i = from + 1; i < path.size(); i++) {
            if (path.get(i).stageId().equals(stageId)) {
                return i;
            }
            if (!path.get(i).optional()) {
                return -1;
            }
        }
        return -1;
    }
}
