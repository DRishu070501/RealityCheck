package com.realitycheck.scenario.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.scenario.change.ChangeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the scenario API. */
public final class ScenarioDtos {

    private ScenarioDtos() {
    }

    /**
     * @param factoryId           may be omitted while exactly one factory exists
     * @param forkAt              required unless a parent is given, whose fork is then copied
     * @param parentScenarioId    branch from this scenario: its changes are copied once, now
     * @param includeFutureOrders default true (or the parent's value): orders placed after the
     *                            fork in recorded history arrive when they really did
     */
    public record CreateScenarioRequest(
            UUID factoryId,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            Instant forkAt,
            UUID parentScenarioId,
            Boolean includeFutureOrders) {
    }

    /** Fields left null are unchanged; an empty description clears it. */
    public record UpdateScenarioRequest(
            @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description,
            Instant forkAt,
            Boolean includeFutureOrders,
            @NotNull Long version) {
    }

    public record ScenarioChangeRequest(@NotNull ChangeType changeType, @NotNull JsonNode payload) {
    }

    /** {@code changeType}, when given, must equal the change's type: a change cannot become another kind. */
    public record UpdateChangeRequest(ChangeType changeType, @NotNull JsonNode payload) {
    }

    /** @param finishedAt null while the run is pending or running */
    public record LatestRun(UUID runId, String status, Instant finishedAt) {
    }

    /**
     * @param inherited copied from the parent scenario when this one was created
     * @param summary   plain-language description built from the payload and current names;
     *                  it contains no simulated numbers
     */
    public record ScenarioChangeResponse(UUID id, int ordinal, ChangeType changeType, boolean inherited,
            JsonNode payload, String summary) {
    }

    /** @param baseline true when the scenario has no changes: the factory as recorded */
    public record ScenarioResponse(UUID id, UUID factoryId, String name, String description, UUID parentScenarioId,
            Instant forkAt, boolean includeFutureOrders, boolean baseline, List<ScenarioChangeResponse> changes,
            LatestRun latestRun, long version, Instant createdAt, Instant updatedAt) {
    }

    public record ScenarioSummaryResponse(UUID id, UUID factoryId, String name, String description,
            UUID parentScenarioId, Instant forkAt, boolean includeFutureOrders, boolean baseline, int changeCount,
            LatestRun latestRun, long version, Instant createdAt, Instant updatedAt) {
    }

    /**
     * @param valid   true when the scenario can be submitted for simulation
     * @param preview configuration before and after the changes; null when a change could not be applied
     */
    public record ScenarioValidationResponse(boolean valid, List<ValidationProblem> errors,
            List<ValidationWarning> warnings, Preview preview, String baseStateHash, String inputHash) {
    }

    /** @param changeId the change at fault, when one is */
    public record ValidationProblem(UUID changeId, String code, String message, String subject) {
    }

    public record ValidationWarning(UUID changeId, int ordinal, String code, String message) {
    }

    /**
     * @param unstartedStonesBefore stones in batches not yet started on their first stage at the fork,
     *                              including future arrivals
     */
    public record Preview(List<PoolPreview> pools, List<StagePreview> stages, long weeklyWorkingSecondsBefore,
            long weeklyWorkingSecondsAfter,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unstartedStonesBefore,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unstartedStonesAfter) {
    }

    /** @param windowsAdded dated capacity reductions the changes add to this pool */
    public record PoolPreview(UUID resourceId, String name, String kind, int capacityBefore, int capacityAfter,
            int windowsAdded) {
    }

    /** @param maxSlotsBefore parallel jobs the base capacities allow; null when the stage needs no pool */
    public record StagePreview(UUID stageId, String code, String name,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stonesPerHourBefore,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stonesPerHourAfter,
            Integer maxSlotsBefore, Integer maxSlotsAfter) {
    }
}
