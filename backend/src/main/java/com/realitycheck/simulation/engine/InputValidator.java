package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.InvalidSimulationInputException.Problem;
import com.realitycheck.simulation.engine.SimulationInput.BatchEntryState;
import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationInput.BreakSpec;
import com.realitycheck.simulation.engine.SimulationInput.CapacityWindow;
import com.realitycheck.simulation.engine.SimulationInput.OrderSpec;
import com.realitycheck.simulation.engine.SimulationInput.PoolSpec;
import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.RouteSpec;
import com.realitycheck.simulation.engine.SimulationInput.RouteStep;
import com.realitycheck.simulation.engine.SimulationInput.ShiftSpec;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Rejects inputs that describe an impossible factory, before any event is processed. Every
 * problem is collected, so a caller can show them all at once.
 */
final class InputValidator {

    static final String INVALID_LIMITS = "INVALID_LIMITS";
    static final String INVALID_CALENDAR = "INVALID_CALENDAR";
    static final String EMPTY_CALENDAR = "EMPTY_CALENDAR";
    static final String DUPLICATE_ID = "DUPLICATE_ID";
    static final String NEGATIVE_CAPACITY = "NEGATIVE_CAPACITY";
    static final String NEGATIVE_COST = "NEGATIVE_COST";
    static final String INVALID_WINDOW = "INVALID_WINDOW";
    static final String NO_STAGES = "NO_STAGES";
    static final String INVALID_SEQUENCE = "INVALID_SEQUENCE";
    static final String NON_POSITIVE_RATE = "NON_POSITIVE_RATE";
    static final String NEGATIVE_SETUP = "NEGATIVE_SETUP";
    static final String INVALID_YIELD = "INVALID_YIELD";
    static final String UNKNOWN_POOL = "UNKNOWN_POOL";
    static final String INVALID_REQUIREMENT = "INVALID_REQUIREMENT";
    static final String REQUIREMENT_EXCEEDS_CAPACITY = "REQUIREMENT_EXCEEDS_CAPACITY";
    static final String UNKNOWN_ORDER = "UNKNOWN_ORDER";
    static final String ORDER_WITHOUT_BATCHES = "ORDER_WITHOUT_BATCHES";
    static final String INVALID_BATCH = "INVALID_BATCH";
    static final String INVALID_ROUTE = "INVALID_ROUTE";

    private static final int SECONDS_PER_DAY = 86_400;

    private InputValidator() {
    }

    static void validate(SimulationInput input) {
        List<Problem> problems = new ArrayList<>();
        long forkTime = input.forkTime();
        boolean limitsValid = input.limits().maxSimulatedSeconds() > 0 && input.limits().maxEvents() > 0;
        if (!limitsValid) {
            problems.add(new Problem(INVALID_LIMITS, "Simulation limits must be positive", null));
        }
        long horizonEnd = limitsValid ? forkTime + input.limits().maxSimulatedSeconds() : forkTime + 1;

        boolean calendarHasTime = validateCalendar(input, problems);
        if (!input.batches().isEmpty() && !calendarHasTime) {
            problems.add(new Problem(EMPTY_CALENDAR,
                    "The calendar has no working time, so the work could never be done", null));
        }

        TreeMap<UUID, PoolSpec> pools = new TreeMap<>();
        for (PoolSpec pool : input.pools()) {
            if (pools.put(pool.id(), pool) != null) {
                problems.add(new Problem(DUPLICATE_ID, "Pool " + pool.name() + " appears more than once",
                        pool.name()));
            }
            if (pool.baseCapacity() < 0) {
                problems.add(new Problem(NEGATIVE_CAPACITY,
                        "Pool " + pool.name() + " has a negative capacity", pool.name()));
            }
            if (pool.hourlyCost() == null || pool.hourlyCost().signum() < 0) {
                problems.add(new Problem(NEGATIVE_COST,
                        "Pool " + pool.name() + " needs an hourly cost of zero or more", pool.name()));
            }
            for (CapacityWindow window : pool.windows()) {
                if (window.to() <= window.from()) {
                    problems.add(new Problem(INVALID_WINDOW,
                            "A capacity window of pool " + pool.name() + " ends before it starts", pool.name()));
                }
            }
        }

        validateStages(input, pools, forkTime, horizonEnd, problems);
        validateOrdersAndBatches(input, forkTime, problems);
        validateRoutes(input, problems);

        if (!problems.isEmpty()) {
            throw new InvalidSimulationInputException(problems);
        }
    }

    /** @return whether the weekly pattern has any working time */
    private static boolean validateCalendar(SimulationInput input, List<Problem> problems) {
        try {
            ZoneId.of(input.calendar().zoneId());
        } catch (DateTimeException | NullPointerException ex) {
            problems.add(new Problem(INVALID_CALENDAR, "The calendar's time zone is not known", null));
        }
        boolean hasTime = false;
        for (ShiftSpec shift : input.calendar().shifts()) {
            boolean valid = shift.dayOfWeek() >= 1 && shift.dayOfWeek() <= 7
                    && shift.startSecondOfDay() >= 0 && shift.endSecondOfDay() <= SECONDS_PER_DAY
                    && shift.endSecondOfDay() > shift.startSecondOfDay();
            for (BreakSpec pause : shift.breaks()) {
                valid &= pause.endSecondOfDay() > pause.startSecondOfDay()
                        && pause.startSecondOfDay() >= shift.startSecondOfDay()
                        && pause.endSecondOfDay() <= shift.endSecondOfDay();
            }
            if (!valid) {
                problems.add(new Problem(INVALID_CALENDAR,
                        "A shift or one of its breaks has an impossible time range", null));
            } else {
                hasTime = true;
            }
        }
        return hasTime;
    }

    private static void validateStages(SimulationInput input, TreeMap<UUID, PoolSpec> pools, long forkTime,
            long horizonEnd, List<Problem> problems) {
        if (input.stages().isEmpty()) {
            if (!input.batches().isEmpty()) {
                problems.add(new Problem(NO_STAGES, "There is work to do but the workflow has no stages", null));
            }
            return;
        }
        TreeSet<Integer> sequences = new TreeSet<>();
        TreeSet<UUID> stageIds = new TreeSet<>();
        for (StageSpec stage : input.stages()) {
            sequences.add(stage.sequence());
            if (!stageIds.add(stage.id())) {
                problems.add(new Problem(DUPLICATE_ID, "Stage " + stage.code() + " appears more than once",
                        stage.code()));
            }
            if (stage.stonesPerHourPerSlot() == null || stage.stonesPerHourPerSlot().signum() <= 0) {
                problems.add(new Problem(NON_POSITIVE_RATE,
                        "Stage " + stage.code() + " needs a processing rate above zero", stage.code()));
            }
            if (stage.setupSeconds() < 0) {
                problems.add(new Problem(NEGATIVE_SETUP,
                        "Stage " + stage.code() + " has a negative setup time", stage.code()));
            }
            if (!isYield(stage.stoneYield()) || !isYield(stage.caratYield())) {
                problems.add(new Problem(INVALID_YIELD,
                        "Stage " + stage.code() + " needs yields above 0 and at most 1", stage.code()));
            }
            if (stage.fixedCostPerJob() == null || stage.fixedCostPerJob().signum() < 0) {
                problems.add(new Problem(NEGATIVE_COST,
                        "Stage " + stage.code() + " needs a fixed cost of zero or more", stage.code()));
            }
            TreeSet<UUID> required = new TreeSet<>();
            for (RequirementSpec requirement : stage.requirements()) {
                PoolSpec pool = pools.get(requirement.poolId());
                if (pool == null) {
                    problems.add(new Problem(UNKNOWN_POOL,
                            "Stage " + stage.code() + " requires a pool that is not in the input", stage.code()));
                    continue;
                }
                if (requirement.unitsPerSlot() < 1 || !required.add(requirement.poolId())) {
                    problems.add(new Problem(INVALID_REQUIREMENT, "Stage " + stage.code()
                            + " must require each pool once, with at least one unit", stage.code()));
                    continue;
                }
                boolean windowsValid = pool.windows().stream().allMatch(w -> w.to() > w.from());
                if (windowsValid && pool.baseCapacity() >= 0 && requirement.unitsPerSlot()
                        > new CapacityProfile(pool.baseCapacity(), pool.windows()).maxCapacity(forkTime, horizonEnd)) {
                    problems.add(new Problem(REQUIREMENT_EXCEEDS_CAPACITY, "Stage " + stage.code() + " needs "
                            + requirement.unitsPerSlot() + " units of " + pool.name()
                            + " per slot, more than the pool ever has, so the stage could never run",
                            stage.code()));
                }
            }
        }
        boolean contiguous = sequences.size() == input.stages().size() && sequences.first() == 1
                && sequences.last() == input.stages().size();
        if (!contiguous) {
            problems.add(new Problem(INVALID_SEQUENCE,
                    "Stage sequences must be exactly 1 to " + input.stages().size(), null));
        }
    }

    private static void validateOrdersAndBatches(SimulationInput input, long forkTime, List<Problem> problems) {
        TreeMap<UUID, Integer> batchesPerOrder = new TreeMap<>();
        for (OrderSpec order : input.orders()) {
            if (batchesPerOrder.put(order.id(), 0) != null) {
                problems.add(new Problem(DUPLICATE_ID, "Order " + order.orderNumber() + " appears more than once",
                        order.orderNumber()));
            }
        }
        TreeSet<UUID> batchIds = new TreeSet<>();
        for (BatchSpec batch : input.batches()) {
            String subject = String.valueOf(batch.id());
            if (!batchIds.add(batch.id())) {
                problems.add(new Problem(DUPLICATE_ID, "Batch " + subject + " appears more than once", subject));
            }
            if (!batchesPerOrder.containsKey(batch.orderId())) {
                problems.add(new Problem(UNKNOWN_ORDER, "Batch " + subject + " belongs to an order that is not "
                        + "in the input", subject));
            } else {
                batchesPerOrder.merge(batch.orderId(), 1, Integer::sum);
            }
            boolean quantitiesValid = batch.stones() != null && batch.carats() != null
                    && batch.stones().signum() >= 0 && batch.carats().signum() >= 0;
            boolean stageValid = batch.stageSequence() >= 1 && batch.stageSequence() <= input.stages().size();
            boolean timesValid = batch.state() != null && switch (batch.state()) {
                case QUEUED -> batch.queueEntryTime() != null && batch.queueEntryTime() <= forkTime;
                case IN_PROGRESS -> batch.startTime() != null && batch.startTime() <= forkTime;
                case FUTURE_ARRIVAL -> batch.arrivalTime() != null;
            };
            if (!quantitiesValid || (!stageValid && !input.stages().isEmpty()) || !timesValid) {
                problems.add(new Problem(INVALID_BATCH, "Batch " + subject + " needs non-negative quantities, a "
                        + "stage in the workflow, and the time its state requires (" + describe(batch.state())
                        + ")", subject));
            }
        }
        for (OrderSpec order : input.orders()) {
            if (batchesPerOrder.getOrDefault(order.id(), 0) == 0) {
                problems.add(new Problem(ORDER_WITHOUT_BATCHES, "Order " + order.orderNumber()
                        + " has no batch left to process and should not be in the input", order.orderNumber()));
            }
        }
    }

    /**
     * Routes name existing stages, each at most once, with a visit share of 1 to 100 percent. A batch's
     * route must exist and include the stage the batch is at when the run starts.
     */
    private static void validateRoutes(SimulationInput input, List<Problem> problems) {
        int stageCount = input.stages().size();
        TreeMap<String, RouteSpec> routes = new TreeMap<>();
        for (RouteSpec route : input.routes()) {
            if (route.code() == null || route.code().isBlank()) {
                problems.add(new Problem(INVALID_ROUTE, "A route needs a code", null));
                continue;
            }
            if (routes.put(route.code(), route) != null) {
                problems.add(new Problem(DUPLICATE_ID, "Route " + route.code() + " appears more than once",
                        route.code()));
            }
            if (route.steps().isEmpty()) {
                problems.add(new Problem(INVALID_ROUTE, "Route " + route.code() + " has no steps", route.code()));
            }
            TreeSet<Integer> seen = new TreeSet<>();
            for (RouteStep step : route.steps()) {
                if (step.stageSequence() < 1 || step.stageSequence() > stageCount) {
                    problems.add(new Problem(INVALID_ROUTE, "Route " + route.code() + " names stage "
                            + step.stageSequence() + ", which is not in the workflow", route.code()));
                } else if (!seen.add(step.stageSequence())) {
                    problems.add(new Problem(INVALID_ROUTE, "Route " + route.code() + " visits stage "
                            + step.stageSequence() + " more than once", route.code()));
                }
                if (step.visitPercent() < 1 || step.visitPercent() > 100) {
                    problems.add(new Problem(INVALID_ROUTE, "Route " + route.code() + " gives stage "
                            + step.stageSequence() + " a visit share outside 1 to 100 percent", route.code()));
                }
            }
        }
        for (BatchSpec batch : input.batches()) {
            if (batch.routeCode() == null) {
                continue;
            }
            RouteSpec route = routes.get(batch.routeCode());
            String subject = String.valueOf(batch.id());
            if (route == null) {
                problems.add(new Problem(INVALID_ROUTE, "Batch " + subject + " follows route " + batch.routeCode()
                        + ", which is not in the input", subject));
            } else if (route.steps().stream().noneMatch(step -> step.stageSequence() == batch.stageSequence())) {
                problems.add(new Problem(INVALID_ROUTE, "Batch " + subject + " is at stage " + batch.stageSequence()
                        + ", which route " + batch.routeCode() + " does not visit", subject));
            }
        }
    }

    private static String describe(BatchEntryState state) {
        if (state == null) {
            return "a state";
        }
        return switch (state) {
            case QUEUED -> "a queue-entry time at or before the fork";
            case IN_PROGRESS -> "a start time at or before the fork";
            case FUTURE_ARRIVAL -> "an arrival time";
        };
    }

    private static boolean isYield(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }
}
