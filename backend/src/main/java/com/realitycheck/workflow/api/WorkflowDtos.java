package com.realitycheck.workflow.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.realitycheck.resource.domain.ResourceKind;
import com.realitycheck.workflow.domain.WorkflowStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the workflow API. Decimals are strings in responses so that no
 * precision is lost in JavaScript.
 *
 * <p>Units: {@code stonesPerHour} is stones per hour per processing slot; {@code setupSeconds} is
 * seconds per job; {@code stoneYield} and {@code caratYield} are fractions in (0, 1];
 * {@code fixedCostPerJob} is in the factory's currency.
 */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    public record CreateWorkflowRequest(
            UUID factoryId,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @NotNull WorkflowStatus status,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid CreateStageRequest> stages) {
    }

    public record CreateStageRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}",
                    message = "must be upper-case letters, digits and underscores") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @NotNull @Min(1) Integer sequence,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 4)
            BigDecimal stonesPerHour,
            @NotNull @Min(0) @Max(31_536_000) Long setupSeconds,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") @Digits(integer = 1, fraction = 6)
            BigDecimal stoneYield,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") @Digits(integer = 1, fraction = 6)
            BigDecimal caratYield,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal fixedCostPerJob,
            @Size(max = 20) List<@NotNull @Valid StageRequirementRequest> requirements) {

        public List<StageRequirementRequest> requirementsOrEmpty() {
            return requirements == null ? List.of() : requirements;
        }
    }

    public record StageRequirementRequest(
            @NotNull UUID resourceId,
            @NotNull @Min(1) @Max(100_000) Integer unitsPerSlot) {
    }

    public record WorkflowSummaryResponse(UUID id, UUID factoryId, String name, WorkflowStatus status,
            int stageCount) {
    }

    public record WorkflowResponse(
            UUID id,
            UUID factoryId,
            String name,
            String description,
            WorkflowStatus status,
            List<StageResponse> stages,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * @param maxSlots how many jobs the stage can run at once with every pool at base capacity:
     *                 the smallest {@code floor(poolCapacity / unitsPerSlot)}. Null when the stage
     *                 needs no resources and is therefore unlimited.
     */
    public record StageResponse(
            UUID id,
            String code,
            String name,
            String description,
            int sequence,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stonesPerHour,
            long setupSeconds,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stoneYield,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal caratYield,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fixedCostPerJob,
            List<StageRequirementResponse> requirements,
            Integer maxSlots,
            String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Long> delayDeciles,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Long> handlingDeciles) {
    }

    public record StageRequirementResponse(UUID resourceId, String resourceName, ResourceKind kind,
            int unitsPerSlot, int poolCapacity) {
    }
}
