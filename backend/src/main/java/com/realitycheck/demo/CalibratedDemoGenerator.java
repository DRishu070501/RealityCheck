package com.realitycheck.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.resource.domain.ResourceKind;
import com.realitycheck.workflow.domain.WorkflowRoute;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The demonstration factory's configuration, built from {@code demo/calibration.json}: rounded patterns
 * measured in one real diamond factory, with generic names and no identifiers (spec 11, task 5).
 *
 * <p>What comes from the calibration: the stages and their kinds, the two routes with the share of packets on
 * each and of packets visiting each optional step, carat yields, how long packets are held at parking,
 * job-work and dispatch stages, how long they stay at working stages, and how close each working stage runs
 * to its capacity on a typical day. What is chosen here, and is synthetic: the volume (ten packets a working
 * day), stones per packet, the number of people and machines, costs and stone yields. Each working stage's
 * rate follows from those choices so that it runs at the measured load. A working stage's measured stay is
 * then split into processing (the rate) and a handling delay (the rest), as the engine models it.
 *
 * <p>Pure and deterministic: the same calibration always gives the same plan. The values are tabulated in
 * docs/demo-data.md.
 */
public final class CalibratedDemoGenerator {

    /** Packets arriving per working day, over all routes. Synthetic. */
    public static final double PACKETS_PER_DAY = 10.0;
    /** Mean rough stones per packet. Synthetic. */
    public static final int MEAN_STONES = 14;
    static final double WORKING_HOURS_PER_DAY = 8.0;
    static final long SETUP_SECONDS = 600;
    /** The intake desk has no measured load; it is given ample room. */
    private static final double INTAKE_LOAD = 0.5;
    /** Share of Tops packets parked: chosen so that 45% of all packets are, as measured (see docs). */
    static final int TOPS_PARKING_PERCENT = 41;

    /**
     * Per-stage stays do not add up to the measured lead times on their own: the stage figures pool every
     * visit by every packet, while a route's lead time is one packet's whole journey (polishing in particular
     * is several departments a packet passes through more than once). Stays are therefore scaled by two
     * factors, one for the stages both routes share and one for polishing (Full route only), fitted once
     * (17 November 2026) so that the overall and per-route median lead times, with and without parking, are
     * within 15% of the calibration. {@code DemoCalibrationTest} checks that they still are.
     */
    static final double SHARED_STAY_MULTIPLIER = 0.55;
    static final double POLISHING_STAY_MULTIPLIER = 3.0;

    public record Pool(String category, ResourceKind kind, String name, int capacity, String hourlyCost) {
    }

    public record Need(String category, int units) {
    }

    /**
     * @param kind            WORKING, PARKING, WAITING or JOB_WORK
     * @param delayDeciles    for a holding kind, ten calendar durations in seconds; null for WORKING
     * @param handlingDeciles for WORKING, ten calendar durations in seconds after processing; null otherwise
     */
    public record Stage(String code, String name, String description, String kind, String stonesPerHour,
            long setupSeconds, String stoneYield, String caratYield, String fixedCostPerJob, List<Need> needs,
            List<Long> delayDeciles, List<Long> handlingDeciles) {
    }

    public record Plan(List<Pool> pools, List<Stage> stages, List<WorkflowRoute> routes,
            Map<String, Integer> routeShares) {
    }

    /** Design choices for one working stage: who works it and what it costs. All synthetic. */
    private record Crew(String workerCategory, String workerName, int workers, String workerCost,
            String machineCategory, String machineName, int machines, String machineCost, int workersPerSlot,
            String stoneYield, String fixedCost, String description) {
    }

    private static final Map<String, Crew> CREWS = new LinkedHashMap<>();

    static {
        CREWS.put("INTAKE", new Crew("INTAKE_CLERK", "Intake clerks", 2, "110.00", null, null, 0, null, 1,
                "1.000000", "40.00", "Receive, weigh and register rough packets against the lot."));
        CREWS.put("IMAGING", new Crew("IMAGING_OPERATOR", "Imaging operators", 3, "180.00", null, null, 0, null, 1,
                "1.000000", "60.00", "Photograph and scan each packet's stones."));
        CREWS.put("LAB", new Crew("LAB_GRADER", "Lab graders", 4, "260.00", null, null, 0, null, 1,
                "1.000000", "120.00", "Grade the rough stones in the laboratory."));
        CREWS.put("INSPECTION", new Crew("INSPECTOR", "Inspectors", 3, "220.00", null, null, 0, null, 1,
                "1.000000", "50.00", "Check each packet before it is planned."));
        CREWS.put("PLANNING", new Crew("PLANNER", "Planners", 4, "300.00", null, null, 0, null, 1,
                "1.000000", "120.00", "Decide how each stone will be cut."));
        CREWS.put("SAWING", new Crew("SAW_OPERATOR", "Saw operators", 4, "240.00", "LASER_SAW", "Laser saws", 4,
                "300.00", 1, "0.990000", "200.00", "Saw the stones along the planned lines."));
        CREWS.put("POLISHING", new Crew("POLISHER", "Polishers", 8, "230.00", "POLISHING_WHEEL", "Polishing wheels",
                4, "140.00", 2, "0.990000", "150.00", "Cut and polish the facets. One wheel is worked by two polishers."));
        CREWS.put("RECUT", new Crew("RECUT_WORKER", "Recut workers", 2, "230.00", null, null, 0, null, 1,
                "1.000000", "80.00", "Correct and finish stones before they leave."));
    }

    private static final Map<String, String> HOLDING_DESCRIPTIONS = Map.of(
            "PARKING", "Packets set aside in the rough store until they are released for manufacturing.",
            "JOB_WORK", "Packets sent to an outside job worker and returned.",
            "DISPATCH_WAIT", "Finished packets waiting to be sent out.");

    private CalibratedDemoGenerator() {
    }

    /** The plan from the calibration on the classpath. */
    public static Plan plan() {
        try (InputStream in = CalibratedDemoGenerator.class.getResourceAsStream("/demo/calibration.json")) {
            if (in == null) {
                throw new IllegalStateException("demo/calibration.json is missing from the classpath");
            }
            return plan(new ObjectMapper().readTree(in));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static Plan plan(JsonNode calibration) {
        return plan(calibration, SHARED_STAY_MULTIPLIER, POLISHING_STAY_MULTIPLIER);
    }

    static Plan plan(JsonNode calibration, double sharedMultiplier, double polishingMultiplier) {
        JsonNode stagesJson = calibration.path("stages");
        List<String> order = stageOrder(calibration.path("routes"));

        // Routes, with Tops given its share of parked packets.
        List<WorkflowRoute> routes = new ArrayList<>();
        Map<String, Integer> shares = new LinkedHashMap<>();
        for (JsonNode route : calibration.path("routes")) {
            String code = route.path("code").asText();
            List<WorkflowRoute.Step> steps = new ArrayList<>();
            for (JsonNode step : route.path("stages")) {
                String stage = step.path("stage").asText();
                if ("TOPS".equals(code) && "SAWING".equals(stage)) {
                    steps.add(new WorkflowRoute.Step("PARKING", TOPS_PARKING_PERCENT));
                }
                steps.add(new WorkflowRoute.Step(stage, (int) Math.round(step.path("visitShare").asDouble() * 100)));
            }
            routes.add(new WorkflowRoute(code, route.path("name").asText(), steps));
            shares.put(code, (int) Math.round(route.path("share").asDouble() * 100));
        }

        // Packets reaching each stage per working day.
        Map<String, Double> arrivals = new LinkedHashMap<>();
        for (WorkflowRoute route : routes) {
            double routePackets = PACKETS_PER_DAY * shares.get(route.code()) / 100.0;
            for (WorkflowRoute.Step step : route.steps()) {
                arrivals.merge(step.stageCode(), routePackets * step.visitPercent() / 100.0, Double::sum);
            }
        }

        Map<String, Pool> pools = new LinkedHashMap<>();
        List<Stage> stages = new ArrayList<>();
        for (String code : order) {
            JsonNode measured = stagesJson.path(code);
            String name = measured.path("name").asText();
            String caratYield = decimal(measured.path("caratYield").asDouble(1.0), 6);
            List<Long> dwellHours = longs(measured.path("dwellHoursDeciles"));
            String kind = measured.path("kind").asText();
            if ("HOLDING".equals(kind) || "JOB_WORK".equals(kind)) {
                String holdingKind = "JOB_WORK".equals(kind) ? "JOB_WORK"
                        : "PARKING".equals(code) ? "PARKING" : "WAITING";
                stages.add(new Stage(code, name, HOLDING_DESCRIPTIONS.get(code), holdingKind, "1.0000", 0,
                        "1.000000", caratYield, "0.00", List.of(), hoursToSeconds(dwellHours, 1.0, 0), null));
                continue;
            }
            Crew crew = CREWS.get(code);
            pools.putIfAbsent(crew.workerCategory(), new Pool(crew.workerCategory(), ResourceKind.EMPLOYEE,
                    crew.workerName(), crew.workers(), crew.workerCost()));
            List<Need> needs = new ArrayList<>();
            if (crew.machineCategory() != null) {
                pools.putIfAbsent(crew.machineCategory(), new Pool(crew.machineCategory(), ResourceKind.MACHINE,
                        crew.machineName(), crew.machines(), crew.machineCost()));
                needs.add(new Need(crew.machineCategory(), 1));
            }
            needs.add(new Need(crew.workerCategory(), crew.workersPerSlot()));
            int slots = crew.machineCategory() == null ? crew.workers() / crew.workersPerSlot()
                    : Math.min(crew.machines(), crew.workers() / crew.workersPerSlot());

            // Run at the measured load: slots x working hours x load = arrivals x hours per packet.
            double load = measured.has("typicalDayLoad") ? measured.path("typicalDayLoad").asDouble() : INTAKE_LOAD;
            double hoursPerPacket = slots * WORKING_HOURS_PER_DAY * load / arrivals.get(code);
            double processingHours = hoursPerPacket - SETUP_SECONDS / 3600.0;
            String rate = decimal(MEAN_STONES / processingHours, 4);

            double multiplier = "POLISHING".equals(code) ? polishingMultiplier : sharedMultiplier;
            stages.add(new Stage(code, name, crew.description(), "WORKING", rate, SETUP_SECONDS, crew.stoneYield(),
                    caratYield, crew.fixedCost(), needs, null,
                    hoursToSeconds(dwellHours, multiplier, hoursPerPacket)));
        }
        return new Plan(List.copyOf(pools.values()), stages, routes, shares);
    }

    /** Every stage any route visits, in the order the routes visit them. */
    private static List<String> stageOrder(JsonNode routes) {
        List<String> order = new ArrayList<>();
        for (JsonNode route : routes) {
            String previous = null;
            for (JsonNode step : route.path("stages")) {
                String stage = step.path("stage").asText();
                if (!order.contains(stage)) {
                    int at = previous == null ? 0 : order.indexOf(previous) + 1;
                    order.add(at, stage);
                }
                previous = stage;
            }
        }
        return order;
    }

    /**
     * Ten durations in seconds, rounded to whole minutes: each measured stay in hours times the multiplier,
     * less the hours already spent processing, never below zero.
     */
    private static List<Long> hoursToSeconds(List<Long> hours, double multiplier, double lessHours) {
        List<Long> seconds = new ArrayList<>();
        for (long h : hours) {
            double remaining = Math.max(0, h * multiplier - lessHours);
            seconds.add(Math.round(remaining * 60) * 60);
        }
        return List.copyOf(seconds);
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asLong()));
        return values;
    }

    private static String decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_EVEN).toPlainString();
    }
}
