package com.realitycheck.workflow.domain;

import java.util.List;

/**
 * A path through a workflow's stages (spec 11). Steps are in ascending stage sequence; a step with a visit
 * percent below 100 is optional and is taken by that share of batches. The first and last steps are always
 * visited.
 */
public record WorkflowRoute(String code, String name, List<Step> steps) {

    public record Step(String stageCode, int visitPercent) {
    }
}
