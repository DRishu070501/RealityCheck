package com.realitycheck.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.scenario.api.ScenarioDtos.CreateScenarioRequest;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioChangeRequest;
import com.realitycheck.scenario.change.ChangeType;
import com.realitycheck.scenario.service.ScenarioService;
import com.realitycheck.workflow.service.WorkflowReader;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The six ready-made scenarios, chosen to show what the calibration found (spec 11), all forked at the same
 * instant: five working days before the seeding instant, so recorded history after the fork exists to
 * compare against. They are created
 * through the scenario service, so every change passes the same validation as user input. No
 * simulation result is pre-populated: every number shown for them is computed when they are run.
 */
@Component
class ScenarioSeedSection implements DemoSeedSection {

    static final String BASELINE = "Current factory operations";

    private static final int WORKING_DAYS_BACK = 5;
    private static final int SCENARIO_COUNT = 6;

    private final ScenarioService scenarioService;
    private final ObjectMapper objectMapper;
    private final WorkflowReader workflowReader;

    ScenarioSeedSection(ScenarioService scenarioService, ObjectMapper objectMapper, WorkflowReader workflowReader) {
        this.scenarioService = scenarioService;
        this.objectMapper = objectMapper;
        this.workflowReader = workflowReader;
    }

    @Override
    public int order() {
        return 50;
    }

    /** Five working days (Monday to Saturday) before the seeding instant, at the same time of day. */
    static Instant forkAt(Instant seededAt) {
        ZonedDateTime at = seededAt.atZone(ZoneId.of(DemoData.TIME_ZONE));
        int stepped = 0;
        while (stepped < WORKING_DAYS_BACK) {
            at = at.minusDays(1);
            if (at.getDayOfWeek() != DayOfWeek.SUNDAY) {
                stepped++;
            }
        }
        return at.toInstant();
    }

    @Override
    public void seed(DemoSeedContext context) {
        Instant fork = forkAt(context.seededAt());
        UUID inspectors = context.id(ResourceSeedSection.key("INSPECTOR"));
        Map<String, UUID> stages = new HashMap<>();
        workflowReader.activeStages(context.factoryId()).forEach(stage -> stages.put(stage.code(), stage.id()));

        scenario(context, fork, 0, BASELINE,
                "The factory as recorded, with nothing changed: the reference the other scenarios are compared with.",
                List.of());
        scenario(context, fork, 1, "Park half as long",
                "Packets set aside in the rough store are released twice as soon. Parking is about two fifths of "
                        + "all lead time in the factory this demonstration is calibrated on.",
                List.of(change(ChangeType.STAGE_DELAY, Map.of("stageId", stages.get("PARKING"), "percent", -50))));
        scenario(context, fork, 2, "Three more inspectors",
                "Three inspectors added to the three there are. Inspection runs below capacity, so this shows "
                        + "what more people do where packets wait for other reasons.",
                List.of(change(ChangeType.RESOURCE_CAPACITY,
                        Map.of("resourceId", inspectors, "mode", "DELTA", "value", 3))));
        scenario(context, fork, 3, "Job worker 30% slower",
                "The outside job worker keeps packets 30% longer than measured.",
                List.of(change(ChangeType.STAGE_DELAY, Map.of("stageId", stages.get("JOB_WORK"), "percent", 30))));
        scenario(context, fork, 4, "Increase production demand by 20%",
                "Every packet not yet started holds 20% more stones and carats.",
                List.of(change(ChangeType.DEMAND_VOLUME, Map.of("percent", 20))));

        List<Map<String, Object>> evening = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            Map<String, Object> shift = new LinkedHashMap<>();
            shift.put("dayOfWeek", day);
            shift.put("startTime", "18:00");
            shift.put("endTime", "22:00");
            evening.add(shift);
        }
        scenario(context, fork, 5, "Introduce an additional shift",
                "An evening shift, Monday to Saturday 18:00 to 22:00, with every pool at its normal capacity.",
                List.of(change(ChangeType.WORKING_SCHEDULE, Map.of("mode", "ADD", "shifts", evening))));
    }

    /** @param index position in the list; creation times are one second apart in this order */
    private void scenario(DemoSeedContext context, Instant fork, int index, String name, String description,
            List<ScenarioChangeRequest> changes) {
        UUID id = context.id("scenario:" + name);
        // One second apart, ending at the seeding instant, so lists show them in this order.
        Instant createdAt = context.seededAt().minusSeconds(SCENARIO_COUNT - 1 - index);
        scenarioService.create(new CreateScenarioRequest(context.factoryId(), name,
                description + " (Demonstration scenario.)", fork, null, true), id, createdAt);
        for (int i = 0; i < changes.size(); i++) {
            scenarioService.addChange(id, changes.get(i), context.id("scenario:" + name + ":change:" + (i + 1)),
                    createdAt);
        }
    }

    private ScenarioChangeRequest change(ChangeType type, Map<String, Object> payload) {
        JsonNode json = objectMapper.valueToTree(payload);
        return new ScenarioChangeRequest(type, json);
    }
}
