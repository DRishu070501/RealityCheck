package com.realitycheck.demo;

import com.realitycheck.factory.service.FactoryService;
import com.realitycheck.factory.service.WorkingTime;
import com.realitycheck.production.api.ProductionDtos.CreateBatchRequest;
import com.realitycheck.production.api.ProductionDtos.CreateOrderRequest;
import com.realitycheck.production.service.EventAppendService;
import com.realitycheck.production.service.OrderService;
import com.realitycheck.resource.service.CapacityTimeline;
import com.realitycheck.resource.service.ResourceReader;
import com.realitycheck.resource.service.ResourceReader.ResourceView;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.RequirementView;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Synthetic orders and the history of working on them, from the anchor up to the seeding instant.
 *
 * <p>The history is produced by a small first-come-first-served scheduler that respects the
 * working calendar and each stage's slot capacity, including the seeded downtime, so the recorded
 * past is one that could really have happened. Durations are varied a little around the
 * configured rates, as recorded work is. This is not the simulation engine: it only manufactures
 * a plausible "actual" history, written through the same services the API uses.
 */
@Component
class ProductionSeedSection implements DemoSeedSection {

    /** A new order every three working hours: a little more than polishing can absorb, so a queue forms. */
    private static final long SECONDS_BETWEEN_ORDERS = 10_800;
    private static final String CUSTOMERS = "ABCDEF";

    private final FactoryService factoryService;
    private final WorkflowReader workflowReader;
    private final ResourceReader resourceReader;
    private final OrderService orderService;
    private final EventAppendService eventAppendService;

    ProductionSeedSection(FactoryService factoryService, WorkflowReader workflowReader,
            ResourceReader resourceReader, OrderService orderService, EventAppendService eventAppendService) {
        this.factoryService = factoryService;
        this.workflowReader = workflowReader;
        this.resourceReader = resourceReader;
        this.orderService = orderService;
        this.eventAppendService = eventAppendService;
    }

    @Override
    public int order() {
        return 40;
    }

    private static final class Batch {
        final int order;
        final int number;
        final int initialStones;
        final BigDecimal initialCarats;
        /** What the batch holds as the scheduler moves it along. */
        BigDecimal stones;
        BigDecimal carats;
        UUID id;

        Batch(int order, int number, int stones, BigDecimal carats) {
            this.order = order;
            this.number = number;
            this.initialStones = stones;
            this.initialCarats = carats;
            this.stones = new BigDecimal(stones).setScale(4);
            this.carats = carats;
        }
    }

    private record Order(int index, UUID id, Instant placedAt, List<Batch> batches) {
    }

    private enum Kind { ARRIVE, FINISH, RECHECK }

    private record Pending(Instant at, long n, Kind kind, Batch batch, int stage, long workSeconds) {
    }

    /** Something to record, in the order it happened. */
    private record Recorded(Instant at, long n, Runnable write) {
    }

    @Override
    public void seed(DemoSeedContext context) {
        UUID factoryId = context.factoryId();
        WorkingTime workingTime = new WorkingTime(factoryService.calendar(factoryId));
        List<StageView> stages = workflowReader.activeStages(factoryId);
        Map<UUID, ResourceView> pools = new HashMap<>();
        Map<UUID, List<CapacityTimeline.Window>> windows = new HashMap<>();
        Instant horizon = context.seededAt().plus(Duration.ofDays(60));
        for (ResourceView pool : resourceReader.forFactory(factoryId)) {
            pools.put(pool.id(), pool);
            windows.put(pool.id(), resourceReader.windows(pool.id(), context.anchor(), horizon));
        }

        List<Order> orders = planOrders(context, workingTime);
        List<Recorded> recorded = new ArrayList<>();
        long[] counter = {0};

        PriorityQueue<Pending> agenda = new PriorityQueue<>(
                Comparator.comparing(Pending::at).thenComparingLong(Pending::n));
        List<Deque<Batch>> queues = new ArrayList<>();
        int[] busy = new int[stages.size()];
        for (int i = 0; i < stages.size(); i++) {
            queues.add(new ArrayDeque<>());
        }
        Map<Integer, Integer> unfinishedBatches = new HashMap<>();
        for (Order order : orders) {
            unfinishedBatches.put(order.index(), order.batches().size());
            for (Batch batch : order.batches()) {
                agenda.add(new Pending(order.placedAt(), counter[0]++, Kind.ARRIVE, batch, 0, 0));
            }
        }
        // Capacity returns at the end of a window with nobody arriving to notice; look again then.
        for (int stage = 0; stage < stages.size(); stage++) {
            for (RequirementView requirement : stages.get(stage).requirements()) {
                for (CapacityTimeline.Window window : windows.get(requirement.resourceId())) {
                    agenda.add(new Pending(window.endsAt(), counter[0]++, Kind.RECHECK, null, stage, 0));
                }
            }
        }
        boolean miscountRecorded = false;

        while (!agenda.isEmpty()) {
            Pending next = agenda.poll();
            if (next.at().isAfter(context.seededAt())) {
                break;
            }
            int stageIndex = next.stage();
            StageView stage = stages.get(stageIndex);
            Instant at = next.at();
            Batch batch = next.batch();

            if (next.kind() == Kind.ARRIVE) {
                queues.get(stageIndex).add(batch);
            } else if (next.kind() == Kind.FINISH) {
                busy[stageIndex]--;
                BigDecimal stonesIn = batch.stones;
                BigDecimal stonesOut = stonesIn.multiply(stage.stoneYield()).setScale(0, RoundingMode.HALF_EVEN)
                        .setScale(4);
                BigDecimal caratsOut = batch.carats.multiply(stage.caratYield()).setScale(4, RoundingMode.HALF_EVEN);
                batch.stones = stonesOut;
                batch.carats = caratsOut;
                String key = "event:" + batch.order + ":" + batch.number + ":" + stageIndex;
                UUID completionId = context.id(key + ":completed");
                long work = next.workSeconds();

                // One completion is first recorded a stone too high, then corrected an hour later.
                boolean miscount = !miscountRecorded && stage.code().equals("CUTTING")
                        && stonesOut.compareTo(stonesIn) < 0;
                BigDecimal recordedStones = miscount ? stonesOut.add(BigDecimal.ONE) : stonesOut;
                recorded.add(new Recorded(at, counter[0]++, () -> eventAppendService.stageCompleted(batch.id,
                        null, at, recordedStones, caratsOut, work, Map.of(), completionId, at)));
                if (miscount) {
                    miscountRecorded = true;
                    Instant correctedAt = workingTime.addWorkingSeconds(at, 3_600);
                    recorded.add(new Recorded(correctedAt, counter[0]++, () -> eventAppendService.correct(
                            completionId, stonesOut, null, null, false,
                            Map.of("reason", "Recount after cutting (synthetic)"),
                            context.id(key + ":correction"), correctedAt)));
                }

                if (stageIndex < stages.size() - 1) {
                    agenda.add(new Pending(at, counter[0]++, Kind.ARRIVE, batch, stageIndex + 1, 0));
                } else if (unfinishedBatches.merge(batch.order, -1, Integer::sum) == 0 && batch.order % 4 != 0) {
                    // Most finished orders leave two working hours later; every fourth still waits.
                    Instant dispatchedAt = workingTime.addWorkingSeconds(at, 7_200);
                    UUID orderId = context.id("order:" + batch.order);
                    recorded.add(new Recorded(dispatchedAt, counter[0]++, () -> eventAppendService.orderDispatched(
                            orderId, dispatchedAt, Map.of(), context.id("event:" + batch.order + ":dispatched"),
                            dispatchedAt)));
                }
            }

            // Start whatever can start at this stage now.
            Instant startAt = workingTime.nextWorkingInstant(at);
            if (startAt.isAfter(at)) {
                agenda.add(new Pending(startAt, counter[0]++, Kind.RECHECK, null, stageIndex, 0));
                continue;
            }
            int slots = slotsAt(stage, pools, windows, at);
            Deque<Batch> queue = queues.get(stageIndex);
            while (!queue.isEmpty() && busy[stageIndex] < slots) {
                Batch starting = queue.poll();
                busy[stageIndex]++;
                String key = "event:" + starting.order + ":" + starting.number + ":" + stageIndex;
                recorded.add(new Recorded(at, counter[0]++, () -> eventAppendService.stageStarted(starting.id, null,
                        at, Map.of(), context.id(key + ":started"), at)));
                long nominal = stage.setupSeconds() + starting.stones.multiply(BigDecimal.valueOf(3_600))
                        .divide(stage.stonesPerHour(), 0, RoundingMode.CEILING).longValueExact();
                // Recorded work runs between 8% faster and 15% slower than the configured rate.
                long variation = 92 + Math.floorMod(starting.order * 31 + starting.number * 17 + stageIndex * 7, 24);
                long work = nominal * variation / 100;
                agenda.add(new Pending(workingTime.addWorkingSeconds(at, work), counter[0]++, Kind.FINISH,
                        starting, stageIndex, work));
            }
        }

        // Orders first, so that every batch exists before its events are written.
        for (Order order : orders) {
            createOrder(context, order);
        }
        recorded.stream()
                .filter(entry -> !entry.at().isAfter(context.seededAt()))
                .sorted(Comparator.comparing(Recorded::at).thenComparingLong(Recorded::n))
                .forEach(entry -> entry.write().run());
    }

    /** Orders placed at a steady rhythm from the anchor until the seeding instant. */
    private static List<Order> planOrders(DemoSeedContext context, WorkingTime workingTime) {
        List<Order> orders = new ArrayList<>();
        for (int i = 1; ; i++) {
            Instant placedAt = workingTime.addWorkingSeconds(context.anchor(), (i - 1) * SECONDS_BETWEEN_ORDERS);
            if (placedAt.isAfter(context.seededAt())) {
                return orders;
            }
            List<Batch> batches = new ArrayList<>();
            int batchCount = i % 3 == 0 ? 2 : 1;
            for (int j = 1; j <= batchCount; j++) {
                int stones = 60 + Math.floorMod(i * 37 + j * 53, 7) * 10;
                BigDecimal caratsPerStone = new BigDecimal("1.10")
                        .add(BigDecimal.valueOf(Math.floorMod(i * 13 + j * 7, 9)).multiply(new BigDecimal("0.05")));
                batches.add(new Batch(i, j, stones,
                        caratsPerStone.multiply(BigDecimal.valueOf(stones)).setScale(4, RoundingMode.HALF_EVEN)));
            }
            orders.add(new Order(i, context.id("order:" + i), placedAt, batches));
        }
    }

    private void createOrder(DemoSeedContext context, Order order) {
        int i = order.index();
        var response = orderService.create(
                new CreateOrderRequest(context.factoryId(), "SDW-%04d".formatted(i),
                        "Demo buyer " + CUSTOMERS.charAt(i % CUSTOMERS.length()),
                        1 + Math.floorMod(i * 7, 5),
                        order.placedAt().plus(Duration.ofDays(7 + i % 4)),
                        order.placedAt(),
                        order.batches().stream()
                                .map(batch -> new CreateBatchRequest(batch.initialStones, batch.initialCarats))
                                .toList()),
                order.id(), order.placedAt());
        for (Batch batch : order.batches()) {
            batch.id = com.realitycheck.common.domain.Ids.child(response.id(), "batch:" + batch.number);
        }
    }

    /** How many jobs the stage can run at once at an instant, given capacity windows. */
    private static int slotsAt(StageView stage, Map<UUID, ResourceView> pools,
            Map<UUID, List<CapacityTimeline.Window>> windows, Instant at) {
        int slots = Integer.MAX_VALUE;
        for (RequirementView requirement : stage.requirements()) {
            int capacity = CapacityTimeline.capacityAt(pools.get(requirement.resourceId()).capacity(),
                    windows.get(requirement.resourceId()), at);
            slots = Math.min(slots, capacity / requirement.unitsPerSlot());
        }
        return slots;
    }
}
