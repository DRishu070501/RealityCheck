package com.realitycheck.resource.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.realitycheck.resource.domain.ResourceKind;
import com.realitycheck.resource.domain.ResourceStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the resource API. Money is a decimal string so that no
 * precision is lost in JavaScript; {@code capacity} counts interchangeable units.
 */
public final class ResourceDtos {

    private static final String CATEGORY = "[A-Z][A-Z0-9_]{1,59}";
    private static final String CATEGORY_MESSAGE = "must be upper-case letters, digits and underscores";
    private static final int MAX_CAPACITY = 100_000;

    private ResourceDtos() {
    }

    public record CreateResourceRequest(
            UUID factoryId,
            @NotNull ResourceKind kind,
            @NotBlank @Pattern(regexp = CATEGORY, message = CATEGORY_MESSAGE) String category,
            @NotBlank @Size(max = 120) String name,
            @NotNull @Min(0) @Max(MAX_CAPACITY) Integer capacity,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal hourlyCost) {
    }

    /** Every field is required; {@code version} must be the one last read, or the update is refused. */
    public record UpdateResourceRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = CATEGORY, message = CATEGORY_MESSAGE) String category,
            @NotNull @Min(0) @Max(MAX_CAPACITY) Integer capacity,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal hourlyCost,
            @NotNull ResourceStatus status,
            @NotNull Long version) {
    }

    public record ResourceResponse(
            UUID id,
            UUID factoryId,
            ResourceKind kind,
            String category,
            String name,
            int capacity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal hourlyCost,
            ResourceStatus status,
            List<RequiredByStage> requiredByStages,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** A stage of the active workflow that takes {@code unitsPerSlot} units of this pool per processing slot. */
    public record RequiredByStage(UUID stageId, String stageCode, String stageName, int sequence, int unitsPerSlot) {
    }

    /** A negative {@code capacityDelta} makes units unavailable for the window; positive adds units. */
    public record CreateAvailabilityRequest(
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull @Min(-MAX_CAPACITY) @Max(MAX_CAPACITY) Integer capacityDelta,
            @NotBlank @Size(max = 200) String reason) {
    }

    public record AvailabilityResponse(UUID id, Instant startsAt, Instant endsAt, int capacityDelta, String reason,
            Instant createdAt) {
    }

    /**
     * @param from     start of the reported range, or null when the pool has no windows and none was asked for
     * @param timeline effective capacity over {@code [from, to)} as contiguous segments
     */
    public record CapacityTimelineResponse(UUID resourceId, int baseCapacity, Instant from, Instant to,
            List<AvailabilityResponse> windows, List<CapacitySegment> timeline) {
    }

    public record CapacitySegment(Instant from, Instant to, int capacity) {
    }
}
