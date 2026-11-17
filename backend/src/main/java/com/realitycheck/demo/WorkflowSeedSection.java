package com.realitycheck.demo;

import com.realitycheck.workflow.api.WorkflowDtos.CreateStageRequest;
import com.realitycheck.workflow.api.WorkflowDtos.CreateWorkflowRequest;
import com.realitycheck.workflow.api.WorkflowDtos.StageRequirementRequest;
import com.realitycheck.workflow.domain.WorkflowStatus;
import com.realitycheck.workflow.service.WorkflowService;
import com.realitycheck.workflow.service.WorkflowService.StageBehaviour;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The calibrated workflow: its stages wired to the seeded pools, the kinds and measured delays of its
 * parking, job-work and dispatch stages, the handling delays of its working stages, and its two routes.
 */
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
        CalibratedDemoGenerator.Plan plan = CalibratedDemoGenerator.plan();
        List<CreateStageRequest> stages = new ArrayList<>();
        Map<String, StageBehaviour> behaviours = new LinkedHashMap<>();
        int sequence = 1;
        for (CalibratedDemoGenerator.Stage stage : plan.stages()) {
            List<StageRequirementRequest> requirements = stage.needs().stream()
                    .map(need -> new StageRequirementRequest(
                            context.id(ResourceSeedSection.key(need.category())), need.units()))
                    .toList();
            stages.add(new CreateStageRequest(stage.code(), stage.name(), stage.description(), sequence++,
                    new BigDecimal(stage.stonesPerHour()), stage.setupSeconds(),
                    new BigDecimal(stage.stoneYield()), new BigDecimal(stage.caratYield()),
                    new BigDecimal(stage.fixedCostPerJob()), requirements));
            behaviours.put(stage.code(), new StageBehaviour(stage.kind(), stage.delayDeciles(),
                    stage.handlingDeciles()));
        }
        workflowService.create(
                new CreateWorkflowRequest(context.factoryId(), DemoData.WORKFLOW_NAME,
                        "Synthetic workflow calibrated on one real factory: two routes, parking, outside job work "
                                + "and dispatch waiting.",
                        WorkflowStatus.ACTIVE, stages),
                context.id(WORKFLOW), context.anchor());
        workflowService.configure(context.id(WORKFLOW), behaviours, plan.routes());
    }
}
