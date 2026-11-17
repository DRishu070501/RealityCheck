package com.realitycheck.production.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.domain.OrderStatus;
import com.realitycheck.resource.domain.ResourceKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request and response shapes for orders, events, the timeline and factory state. Everything
 * here is recorded history, so each top-level response carries {@code source: "ACTUAL"}.
 * Stones and carats are separate quantities; both are decimal strings in responses.
 */
public final class ProductionDtos {

    public static final String ACTUAL = "ACTUAL";

    private ProductionDtos() {
    }

    // ---- Orders ---------------------------------------------------------------------------

    /** {@code placedAt} defaults to now and may not be in the future. Priority 5 is the most urgent. */
    public record CreateOrderRequest(
            UUID factoryId,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]*",
                    message = "may contain letters, digits, dots, slashes, dashes and underscores") String orderNumber,
            @Size(max = 120) String customerRef,
            @NotNull @Min(1) @Max(5) Integer priority,
            Instant dueAt,
            Instant placedAt,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid CreateBatchRequest> batches) {
    }

    /** @param routeCode a route of the active workflow (spec 11); omitted to visit every stage */
    public record CreateBatchRequest(
            @NotNull @Min(1) @Max(1_000_000) Integer stoneCount,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 4)
            BigDecimal roughCarats,
            @Size(max = 40) String routeCode) {

        public CreateBatchRequest(Integer stoneCount, BigDecimal roughCarats) {
            this(stoneCount, roughCarats, null);
        }
    }

    /**
     * @param delayed       finished after it was due, or unfinished and past due now
     * @param totalStones   stones the order started with, summed over its batches
     * @param batchesCompleted how many batches have finished the last stage
     */
    public record OrderResponse(
            UUID id,
            UUID factoryId,
            String orderNumber,
            String customerRef,
            int priority,
            OrderStatus status,
            Instant placedAt,
            Instant dueAt,
            Instant completedAt,
            boolean delayed,
            int batchCount,
            int batchesCompleted,
            int totalStones,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalRoughCarats,
            String source) {
    }

    public record OrderDetailResponse(OrderResponse order, List<BatchResponse> batches, List<EventResponse> events) {
    }

    /**
     * @param stoneCount    whole stones the batch started with
     * @param currentStones what it holds now: the output of its last completed stage
     * @param currentStageId null once the batch has finished
     */
    public record BatchResponse(
            UUID id,
            int batchNumber,
            int stoneCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal roughCarats,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentStones,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentCarats,
            UUID currentStageId,
            String currentStageCode,
            String currentStageName,
            Integer currentStageSequence,
            BatchStatus status) {
    }

    // ---- Events ---------------------------------------------------------------------------

    /**
     * Records something that happened. Which fields apply depends on {@code eventType}:
     * <ul>
     *   <li>{@code STAGE_STARTED}: {@code batchId}.</li>
     *   <li>{@code STAGE_COMPLETED}: {@code batchId}, {@code stoneQuantity} and {@code caratQuantity}
     *       (what left the stage), optionally {@code durationSeconds} (working time spent).</li>
     *   <li>{@code ORDER_DISPATCHED}: {@code orderId}.</li>
     *   <li>{@code CORRECTION}: {@code correctsEventId}, and either {@code voids: true} or at least
     *       one replacement of {@code stoneQuantity}, {@code caratQuantity}, {@code durationSeconds}.</li>
     * </ul>
     * {@code stageId} is optional; when given it must be the batch's current stage.
     * {@code occurredAt} defaults to now and may not be in the future. {@code nextStageId}, for
     * {@code STAGE_COMPLETED} only and optional: a later stage of the batch's route to queue it at,
     * skipping optional steps; by default the route's next step.
     */
    public record AppendEventRequest(
            @NotNull EventType eventType,
            UUID batchId,
            UUID orderId,
            UUID stageId,
            Instant occurredAt,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal stoneQuantity,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal caratQuantity,
            @Min(0) Long durationSeconds,
            UUID correctsEventId,
            Boolean voids,
            @Size(max = 20) Map<@Size(max = 60) String, Object> payload,
            UUID nextStageId) {

        public AppendEventRequest(EventType eventType, UUID batchId, UUID orderId, UUID stageId, Instant occurredAt,
                BigDecimal stoneQuantity, BigDecimal caratQuantity, Long durationSeconds, UUID correctsEventId,
                Boolean voids, Map<String, Object> payload) {
            this(eventType, batchId, orderId, stageId, occurredAt, stoneQuantity, caratQuantity, durationSeconds,
                    correctsEventId, voids, payload, null);
        }
    }

    /**
     * An event as recorded, plus what is known about it now.
     *
     * @param recordedAt  when the row was written; {@code occurredAt} is when it happened
     * @param stoneQuantity the effective value: the latest correction's if one was made
     * @param corrected   a correction has replaced at least one value; {@code original} holds what was first recorded
     * @param voided      a correction cancelled this event; it no longer counts
     */
    public record EventResponse(
            UUID id,
            long seq,
            EventType eventType,
            Instant occurredAt,
            Instant recordedAt,
            UUID orderId,
            String orderNumber,
            UUID batchId,
            Integer batchNumber,
            UUID stageId,
            String stageCode,
            String stageName,
            UUID resourceId,
            String resourceName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stoneQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal caratQuantity,
            Long durationSeconds,
            Map<String, Object> payload,
            UUID correctsEventId,
            boolean voids,
            boolean corrected,
            boolean voided,
            OriginalValues original,
            String source) {
    }

    public record OriginalValues(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stoneQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal caratQuantity,
            Long durationSeconds) {
    }

    // ---- Timeline summary -----------------------------------------------------------------

    /** Event counts per bucket of factory-local time. Corrections and voided events are not counted. */
    public record TimelineSummaryResponse(String bucket, String timeZone, Instant firstEventAt, Instant lastEventAt,
            List<TimelineBucket> buckets, String source) {
    }

    public record TimelineBucket(Instant start, long total, Map<EventType, Long> byType) {
    }

    // ---- Factory state --------------------------------------------------------------------

    /**
     * The factory as it stood at {@code at}, rebuilt from the event history. Corrections apply
     * whenever they were recorded: the answer is what is now known about that moment.
     */
    public record FactoryStateResponse(
            UUID factoryId,
            Instant at,
            Map<OrderStatus, Long> orders,
            List<StageState> stages,
            List<BatchState> batches,
            List<ResourceState> resources,
            String source) {
    }

    /**
     * @param queued     batches waiting at the stage
     * @param inProgress batches being worked on at the stage
     * @param completed  batches that have finished the stage
     */
    public record StageState(UUID stageId, String code, String name, int sequence, int queued, int inProgress,
            int completed) {
    }

    /**
     * @param inputStones what the batch holds on entering its current stage, or its final output when completed
     * @param waitingSince when it entered the current stage's queue; null once in progress or completed
     * @param routeCode    the route it follows (spec 11); absent when it visits every stage
     */
    public record BatchState(
            UUID batchId,
            UUID orderId,
            String orderNumber,
            int batchNumber,
            int priority,
            BatchStatus status,
            UUID stageId,
            String stageCode,
            Integer stageSequence,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inputStones,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inputCarats,
            Instant waitingSince,
            Instant startedAt,
            Instant completedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String routeCode) {
    }

    /**
     * @param baseCapacity configured units
     * @param capacity     units available at that instant, after availability windows
     * @param unitsInUse   units held by batches in progress then
     */
    public record ResourceState(UUID resourceId, String name, ResourceKind kind, int baseCapacity, int capacity,
            int unitsInUse) {
    }

    // ---- Overview -------------------------------------------------------------------------

    /**
     * What the dashboard shows about the present, all from recorded history.
     *
     * @param asOf        the instant the figures describe
     * @param dailyOutput finished stones and carats per factory-local day, oldest first
     */
    public record FactoryOverviewResponse(
            UUID factoryId,
            Instant asOf,
            OrderCounts orders,
            List<StageState> stages,
            List<ResourceState> resources,
            UnitsInUse machines,
            UnitsInUse employees,
            List<DailyOutput> dailyOutput,
            List<EventResponse> recentEvents,
            String source) {
    }

    /** {@code active} is pending plus in progress. {@code delayed} counts late orders of any status. */
    public record OrderCounts(long total, long active, long pending, long inProgress, long completed,
            long dispatched, long delayed) {
    }

    /** A snapshot of this instant, not utilization over time: units held by batches in progress. */
    public record UnitsInUse(int inUse, int capacity) {
    }

    public record DailyOutput(
            LocalDate date,
            int batchesCompleted,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stonesCompleted,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal caratsCompleted) {
    }
}
