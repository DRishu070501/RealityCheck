package com.realitycheck.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.demo.CalibratedDemoGenerator.Plan;
import com.realitycheck.demo.CalibratedDemoGenerator.Pool;
import com.realitycheck.demo.CalibratedDemoGenerator.Stage;
import com.realitycheck.simulation.engine.SimulationEngine;
import com.realitycheck.simulation.engine.SimulationInput;
import com.realitycheck.simulation.engine.SimulationInput.BatchEntryState;
import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationInput.BottleneckConfig;
import com.realitycheck.simulation.engine.SimulationInput.BreakSpec;
import com.realitycheck.simulation.engine.SimulationInput.CalendarSpec;
import com.realitycheck.simulation.engine.SimulationInput.OrderSpec;
import com.realitycheck.simulation.engine.SimulationInput.PoolSpec;
import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.RouteSpec;
import com.realitycheck.simulation.engine.SimulationInput.RouteStep;
import com.realitycheck.simulation.engine.SimulationInput.ShiftSpec;
import com.realitycheck.simulation.engine.SimulationInput.SimulationLimits;
import com.realitycheck.simulation.engine.SimulationInput.StageKind;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import com.realitycheck.simulation.engine.SimulationResult;
import com.realitycheck.simulation.engine.SimulationResult.TerminationReason;
import com.realitycheck.workflow.domain.WorkflowRoute;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The calibrated demonstration factory, run by the engine on a long, steady stream of packets with nothing
 * in progress at the start, reproduces the measured lead times it was calibrated from (spec 11, task 9).
 * Fast: no database. {@code DemoCalibrationIT} checks the same through the seeded factory.
 */
class DemoCalibrationTest {

    private static final double DAY = 86_400.0;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 1, 5);

    @Test
    void theSimulatedFactoryReproducesTheMeasuredLeadTimes() throws Exception {
        JsonNode calibration = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/demo/calibration.json"));
        Plan plan = CalibratedDemoGenerator.plan();

        SimulationResult all = new SimulationEngine().run(input(plan, 60, null));
        SimulationResult tops = new SimulationEngine().run(input(plan, 60, "TOPS"));
        SimulationResult full = new SimulationEngine().run(input(plan, 60, "FULL"));

        assertThat(all.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertMedians(all, calibration.path("leadDays").path("median").asDouble(),
                calibration.path("manufacturingDays").path("median").asDouble());
        assertMedians(tops, calibration.path("routes").get(0).path("leadMedianDays").asDouble(),
                calibration.path("routes").get(0).path("manufacturingMedianDays").asDouble());
        assertMedians(full, calibration.path("routes").get(1).path("leadMedianDays").asDouble(),
                calibration.path("routes").get(1).path("manufacturingMedianDays").asDouble());
    }

    /** Median lead time with and without parking, each within 15% of the measured one. */
    private static void assertMedians(SimulationResult result, double leadDays, double manufacturingDays) {
        assertThat(days(result.summary().batchLeadMedianSeconds())).as("median lead days")
                .isCloseTo(leadDays, withinPercent(15));
        assertThat(days(result.summary().batchManufacturingMedianSeconds())).as("median days without parking")
                .isCloseTo(manufacturingDays, withinPercent(15));
    }

    private static org.assertj.core.data.Percentage withinPercent(int percent) {
        return org.assertj.core.data.Percentage.withPercentage(percent);
    }

    private static double days(Long seconds) {
        return seconds / DAY;
    }

    /** Four lots a working day of two or three packets, as the seed plans them; only one route when given. */
    static SimulationInput input(Plan plan, int workingDays, String onlyRoute) {
        Map<String, UUID> poolIds = new HashMap<>();
        List<PoolSpec> pools = new ArrayList<>();
        for (Pool pool : plan.pools()) {
            UUID id = uuid("pool:" + pool.category());
            poolIds.put(pool.category(), id);
            pools.add(new PoolSpec(id, SimulationInput.ResourceKind.valueOf(pool.kind().name()), pool.name(),
                    pool.capacity(), new BigDecimal(pool.hourlyCost()), List.of()));
        }
        Map<String, Integer> sequenceOf = new HashMap<>();
        List<StageSpec> stages = new ArrayList<>();
        for (Stage stage : plan.stages()) {
            int sequence = stages.size() + 1;
            sequenceOf.put(stage.code(), sequence);
            stages.add(new StageSpec(uuid("stage:" + stage.code()), stage.code(), sequence,
                    new BigDecimal(stage.stonesPerHour()), stage.setupSeconds(), new BigDecimal(stage.stoneYield()),
                    new BigDecimal(stage.caratYield()), new BigDecimal(stage.fixedCostPerJob()),
                    stage.needs().stream().map(n -> new RequirementSpec(poolIds.get(n.category()), n.units())).toList(),
                    StageKind.valueOf(stage.kind()), stage.delayDeciles() == null ? List.of() : stage.delayDeciles(),
                    stage.handlingDeciles() == null ? List.of() : stage.handlingDeciles()));
        }
        List<RouteSpec> routes = new ArrayList<>();
        for (WorkflowRoute route : plan.routes()) {
            routes.add(new RouteSpec(route.code(), route.steps().stream()
                    .map(step -> new RouteStep(sequenceOf.get(step.stageCode()), step.visitPercent())).toList()));
        }

        List<ShiftSpec> shifts = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            shifts.add(new ShiftSpec(day, 9 * 3600, 18 * 3600, List.of(new BreakSpec(13 * 3600, 14 * 3600))));
        }
        long fork = FIRST_DAY.atTime(9, 0).atZone(ZONE).toEpochSecond();

        List<OrderSpec> orders = new ArrayList<>();
        List<BatchSpec> batches = new ArrayList<>();
        int lot = 0;
        int packet = 0;
        int[] hours = {9, 11, 14, 16};
        int day = 0;
        for (LocalDate date = FIRST_DAY; day < workingDays; date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            day++;
            for (int hour : hours) {
                lot++;
                long arrival = date.atTime(hour, 0).atZone(ZONE).toEpochSecond();
                UUID orderId = uuid("order:" + lot);
                orders.add(new OrderSpec(orderId, "L" + lot, 3, null));
                int packets = lot % 2 == 0 ? 3 : 2;
                for (int p = 0; p < packets; p++) {
                    packet++;
                    String route = DemoProductionPlan.routeOf(packet, plan.routeShares());
                    if (onlyRoute != null && !onlyRoute.equals(route)) {
                        continue;
                    }
                    int stones = DemoProductionPlan.stonesOf(packet);
                    batches.add(new BatchSpec(uuid("batch:" + packet), orderId, BatchEntryState.FUTURE_ARRIVAL,
                            1, BigDecimal.valueOf(stones), BigDecimal.valueOf(stones).multiply(new BigDecimal("1.2")),
                            null, null, arrival, route));
                }
            }
        }
        List<OrderSpec> used = orders.stream()
                .filter(o -> batches.stream().anyMatch(b -> b.orderId().equals(o.id())))
                .toList();
        return new SimulationInput(fork, new CalendarSpec("Asia/Kolkata", shifts), stages, pools, used, batches,
                SimulationLimits.defaults(), BottleneckConfig.defaults(), routes);
    }

    private static UUID uuid(String key) {
        return UUID.nameUUIDFromBytes(("calibration-test:" + key).getBytes(StandardCharsets.UTF_8));
    }
}
