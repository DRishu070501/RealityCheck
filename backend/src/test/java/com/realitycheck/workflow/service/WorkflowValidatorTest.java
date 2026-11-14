package com.realitycheck.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.common.error.FieldViolation;
import com.realitycheck.resource.domain.ResourceKind;
import com.realitycheck.resource.domain.ResourceStatus;
import com.realitycheck.resource.service.ResourceReader.ResourceView;
import com.realitycheck.workflow.api.WorkflowDtos.CreateStageRequest;
import com.realitycheck.workflow.api.WorkflowDtos.StageRequirementRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowValidatorTest {

    private static final UUID FACTORY = UUID.randomUUID();
    private static final UUID OTHER_FACTORY = UUID.randomUUID();
    private static final UUID WHEELS = UUID.randomUUID();
    private static final UUID POLISHERS = UUID.randomUUID();

    private final Map<UUID, ResourceView> pools = Map.of(
            WHEELS, pool(WHEELS, FACTORY, ResourceStatus.ACTIVE),
            POLISHERS, pool(POLISHERS, FACTORY, ResourceStatus.ACTIVE));

    @Test
    void sequentialStagesWithKnownPoolsAreValid() {
        List<CreateStageRequest> stages = List.of(
                stage("CUTTING", 1),
                stage("POLISHING", 2, need(WHEELS, 1), need(POLISHERS, 2)));

        assertThat(WorkflowValidator.validate(FACTORY, stages, pools)).isEmpty();
    }

    @Test
    void stageOrderInTheRequestDoesNotMatter() {
        assertThat(WorkflowValidator.validate(FACTORY, List.of(stage("B", 2), stage("A", 1)), pools)).isEmpty();
    }

    @Test
    void aGapInTheSequenceIsRejected() {
        assertThat(WorkflowValidator.validate(FACTORY, List.of(stage("A", 1), stage("B", 3)), pools))
                .containsExactly(new FieldViolation("stages[1].sequence", "must be between 1 and 2 with no gaps"));
    }

    @Test
    void aRepeatedSequenceIsRejected() {
        assertThat(WorkflowValidator.validate(FACTORY, List.of(stage("A", 1), stage("B", 1)), pools))
                .containsExactly(new FieldViolation("stages[1].sequence", "is used by more than one stage"));
    }

    @Test
    void aRepeatedCodeIsRejected() {
        assertThat(WorkflowValidator.validate(FACTORY, List.of(stage("A", 1), stage("A", 2)), pools))
                .containsExactly(new FieldViolation("stages[1].code", "is used by more than one stage"));
    }

    @Test
    void anUnknownPoolIsRejected() {
        UUID missing = UUID.randomUUID();

        assertThat(WorkflowValidator.validate(FACTORY, List.of(stage("A", 1, need(missing, 1))), pools))
                .containsExactly(new FieldViolation("stages[0].requirements[0].resourceId", "does not exist"));
    }

    @Test
    void aPoolOfAnotherFactoryIsRejected() {
        UUID foreign = UUID.randomUUID();
        Map<UUID, ResourceView> withForeign = Map.of(foreign, pool(foreign, OTHER_FACTORY, ResourceStatus.ACTIVE));

        assertThat(WorkflowValidator.validate(FACTORY, List.of(stage("A", 1, need(foreign, 1))), withForeign))
                .containsExactly(new FieldViolation("stages[0].requirements[0].resourceId",
                        "belongs to a different factory"));
    }

    @Test
    void anInactivePoolIsRejected() {
        UUID retired = UUID.randomUUID();
        Map<UUID, ResourceView> withRetired = Map.of(retired, pool(retired, FACTORY, ResourceStatus.INACTIVE));

        assertThat(WorkflowValidator.validate(FACTORY, List.of(stage("A", 1, need(retired, 1))), withRetired))
                .containsExactly(new FieldViolation("stages[0].requirements[0].resourceId", "is not active"));
    }

    @Test
    void aPoolListedTwiceForOneStageIsRejected() {
        assertThat(WorkflowValidator.validate(FACTORY, List.of(stage("A", 1, need(WHEELS, 1), need(WHEELS, 2))),
                pools))
                .containsExactly(new FieldViolation("stages[0].requirements[1].resourceId",
                        "is listed more than once for this stage"));
    }

    @Test
    void maxSlotsIsLimitedByTheScarcestPool() {
        // 6 wheels at 1 per slot allow 6; 13 polishers at 2 per slot allow 6 (one polisher is left over).
        assertThat(WorkflowValidator.maxSlots(List.of(new int[] {1, 6}, new int[] {2, 13}))).isEqualTo(6);
        assertThat(WorkflowValidator.maxSlots(List.of(new int[] {1, 6}, new int[] {2, 7}))).isEqualTo(3);
    }

    @Test
    void aPoolTooSmallForOneSlotGivesZeroSlots() {
        assertThat(WorkflowValidator.maxSlots(List.of(new int[] {2, 1}))).isZero();
    }

    @Test
    void aStageWithoutRequirementsIsUnlimited() {
        assertThat(WorkflowValidator.maxSlots(List.of())).isNull();
    }

    private static CreateStageRequest stage(String code, int sequence, StageRequirementRequest... needs) {
        return new CreateStageRequest(code, code, null, sequence, new BigDecimal("10"), 0L, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, List.of(needs));
    }

    private static StageRequirementRequest need(UUID pool, int units) {
        return new StageRequirementRequest(pool, units);
    }

    private static ResourceView pool(UUID id, UUID factory, ResourceStatus status) {
        return new ResourceView(id, factory, ResourceKind.MACHINE, "CATEGORY", "Pool " + id, 6,
                new BigDecimal("100.00"), status);
    }
}
