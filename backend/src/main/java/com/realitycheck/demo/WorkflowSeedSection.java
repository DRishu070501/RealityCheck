package com.realitycheck.demo;

import com.realitycheck.workflow.api.WorkflowDtos.CreateStageRequest;
import com.realitycheck.workflow.api.WorkflowDtos.CreateWorkflowRequest;
import com.realitycheck.workflow.api.WorkflowDtos.StageRequirementRequest;
import com.realitycheck.workflow.domain.WorkflowStatus;
import com.realitycheck.workflow.service.WorkflowService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** The eight-stage workflow, from rough intake to dispatch, wired to the seeded pools. */
@Component
class WorkflowSeedSection implements DemoSeedSection {

    static final String WORKFLOW = "workflow";

    private final WorkflowService workflowService;

    WorkflowSeedSection(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public void seed(DemoSeedContext context) {
        List<CreateStageRequest> stages = new ArrayList<>();
        int sequence = 1;
        for (DemoData.Stage stage : DemoData.STAGES) {
            List<StageRequirementRequest> requirements = stage.needs().stream()
                    .map(need -> new StageRequirementRequest(
                            context.id(ResourceSeedSection.key(need.category())), need.units()))
                    .toList();
            stages.add(new CreateStageRequest(stage.code(), stage.name(), stage.description(), sequence++,
                    new BigDecimal(stage.stonesPerHour()), stage.setupSeconds(),
                    new BigDecimal(stage.stoneYield()), new BigDecimal(stage.caratYield()),
                    new BigDecimal(stage.fixedCostPerJob()), requirements));
        }
        workflowService.create(
                new CreateWorkflowRequest(context.factoryId(), DemoData.WORKFLOW_NAME,
                        "Synthetic eight-stage diamond manufacturing workflow.", WorkflowStatus.ACTIVE, stages),
                context.id(WORKFLOW), context.anchor());
    }
}
