package com.realitycheck.production.service;

import com.realitycheck.production.service.BatchHistory.Stop;
import com.realitycheck.workflow.service.WorkflowReader.RouteView;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import java.util.List;
import java.util.UUID;

/** The path a batch takes through the active workflow: its route's steps, or every stage when it has none. */
public final class BatchPaths {

    private BatchPaths() {
    }

    /**
     * @param routeCode the batch's route; null for every stage in sequence
     * @throws IllegalArgumentException when the workflow has no route with that code
     */
    public static List<Stop> of(List<StageView> stages, List<RouteView> routes, String routeCode) {
        if (routeCode == null) {
            return BatchHistory.linear(stages.stream().map(StageView::id).toList());
        }
        RouteView route = route(routes, routeCode);
        return route.steps().stream().map(step -> new Stop(step.stageId(), step.visitPercent() < 100)).toList();
    }

    /** The first stage a batch on {@code routeCode} is queued at. */
    public static UUID firstStage(List<StageView> stages, List<RouteView> routes, String routeCode) {
        return of(stages, routes, routeCode).get(0).stageId();
    }

    public static RouteView route(List<RouteView> routes, String routeCode) {
        return routes.stream().filter(r -> r.code().equals(routeCode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The workflow has no route " + routeCode));
    }
}
