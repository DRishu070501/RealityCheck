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
import com.realitycheck.workflow.service.WorkflowReader.RouteStepView;
import com.realitycheck.workflow.service.WorkflowReader.RouteView;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import jakarta.persistence.EntityManager;
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
 * Synthetic lots and the history of working on them, from the anchor up to the seeding instant.
 *
 * <p>Four lots arrive each working day, of two or three packets each (about ten packets a day). Each packet
 * follows a route; at an optional step it is sent on or not, at a working stage it waits for a free slot and
 * is processed, then stays for a handling time before it moves on; at a parking, job-work or dispatch stage it
 * is held for a measured time. Movements happen in working hours. The history is produced by a small
 * first-come-first-served scheduler that respects the calendar and every pool's capacity, including the
 * seeded absence, so the recorded past is one that could have happened. Its durations are drawn from the same
 * calibrated figures but independently of the simulation engine (spread within each tenth, its own choices of
 * optional steps, processing between 8% faster and 15% slower than the rate), as recorded work would be. It
 * is written through the same services the API uses.
 */
@Component
class ProductionSeedSection implements DemoSeedSection {

    /** A new lot every two working hours: 09:00, 11:00, 14:00 and 16:00. */
    private static final long SECONDS_BETWEEN_LOTS = 7_200;
    private static final String CUSTOMERS = "ABCDEF";

    private final FactoryService factoryService;
    private final WorkflowReader workflowReader;
    private final ResourceReader resourceReader;
    private final OrderService orderService;
    private final EventAppendService eventAppendService;
    private final EntityManager entityManager;

    ProductionSeedSection(FactoryService factoryService, WorkflowReader workflowReader,
            ResourceReader resourceReader, OrderService orderService, EventAppendService eventAppendService,
            EntityManager entityManager) {
        this.factoryService = factoryService;
        this.workflowReader = workflowReader;
        this.resourceReader = resourceReader;
        this.orderService = orderService;
        this.eventAppendService = eventAppendService;
        this.entityManager = entityManager;
    }

    /** Writes since the persistence context was last cleared; see {@link #written()}. */
    private int sinceClear;

    /**
     * The whole seed is one transaction, so every entity written stays managed and each flush checks them
     * all. Each service call reads what it needs afresh, so they can be let go every few hundred writes.
     */
    private void written() {
        if (++sinceClear >= 200) {
            entityManager.flush();
            entityManager.clear();
            sinceClear = 0;
        }
    }

    @Override
    public int order() {
        return 40;
    }

    private static final class Packet {
        final int lot;
        final int number;
        /** Position among all packets, from 1: the key of every deterministic choice about it. */
        final int key;
        final String route;
        final int initialStones;
        final BigDecimal initialCarats;
        /** The stages it will visit, in order: its route with each optional step taken or not. */
        List<StageView> path;
        BigDecimal stones;
        BigDecimal carats;
        UUID id;

        Packet(int lot, int number, int key, String route, int stones, BigDecimal carats) {
            this.lot = lot;
            this.number = number;
            this.key = key;
            this.route = route;
            this.initialStones = stones;
            this.initialCarats = carats;
            this.stones = new BigDecimal(stones).setScale(4);
            this.carats = carats;
        }
    }

    private record Lot(int index, UUID id, Instant placedAt, List<Packet> packets) {
    }

    private enum Kind { ARRIVE, FINISH, RECHECK }

    /** @param step position on the packet's path; for RECHECK, the stage index */
    private record Pending(Instant at, long n, Kind kind, Packet packet, int step, long workSeconds) {
    }

    /** Something to record, in the order it happened. */
    private record Recorded(Instant at, long n, Runnable write) {
    }

    @Override
    public void seed(DemoSeedContext context) {
        UUID factoryId = context.factoryId();
        WorkingTime workingTime = new WorkingTime(factoryService.calendar(factoryId));
        List<StageView> stages = workflowReader.activeStages(factoryId);
        Map<String, RouteView> routes = new HashMap<>();
        workflowReader.activeRoutes(factoryId).forEach(route -> routes.put(route.code(), route));
        Map<UUID, Integer> stageIndex = new HashMap<>();
        for (int i = 0; i < stages.size(); i++) {
            stageIndex.put(stages.get(i).id(), i);
        }
        Map<UUID, ResourceView> pools = new HashMap<>();
        Map<UUID, List<CapacityTimeline.Window>> windows = new HashMap<>();
        Instant horizon = context.seededAt().plus(Duration.ofDays(60));
        for (ResourceView pool : resourceReader.forFactory(factoryId)) {
            pools.put(pool.id(), pool);
            windows.put(pool.id(), resourceReader.windows(pool.id(), context.anchor(), horizon));
        }

        List<Lot> lots = planLots(context, workingTime);
        for (Lot lot : lots) {
            for (Packet packet : lot.packets()) {
                packet.path = pathOf(packet, routes.get(packet.route), stages, stageIndex);
            }
        }
        List<Recorded> recorded = new ArrayList<>();
        long[] counter = {0};

        PriorityQueue<Pending> agenda = new PriorityQueue<>(
                Comparator.comparing(Pending::at).thenComparingLong(Pending::n));
        List<Deque<Packet>> queues = new ArrayList<>();
        int[] busy = new int[stages.size()];
        for (int i = 0; i < stages.size(); i++) {
            queues.add(new ArrayDeque<>());
        }
        Map<Integer, Integer> unfinished = new HashMap<>();
        for (Lot lot : lots) {
            unfinished.put(lot.index(), lot.packets().size());
            for (Packet packet : lot.packets()) {
                agenda.add(new Pending(lot.placedAt(), counter[0]++, Kind.ARRIVE, packet, 0, 0));
            }
        }
        // Capacity returns at the end of a window with nobody arriving to notice; look again then.
        for (int s = 0; s < stages.size(); s++) {
            for (RequirementView requirement : stages.get(s).requirements()) {
                for (CapacityTimeline.Window window : windows.get(requirement.resourceId())) {
                    agenda.add(new Pending(window.endsAt(), counter[0]++, Kind.RECHECK, null, s, 0));
                }
            }
        }
        boolean miscountRecorded = false;
        int finishedLots = 0;

        while (!agenda.isEmpty()) {
            Pending next = agenda.poll();
            if (next.at().isAfter(context.seededAt())) {
                break;
            }
            Instant at = next.at();
            Packet packet = next.packet();
            int s = next.kind() == Kind.RECHECK ? next.step() : stageIndex.get(packet.path.get(next.step()).id());
            StageView stage = stages.get(s);
            boolean holding = !"WORKING".equals(stage.kind());

            if (next.kind() == Kind.ARRIVE) {
                if (holding) {
                    // Held from arrival, without capacity, and released in working hours.
                    String key = "event:" + packet.lot + ":" + packet.number + ":" + s;
                    recorded.add(new Recorded(at, counter[0]++, () -> eventAppendService.stageStarted(packet.id,
                            null, at, Map.of(), context.id(key + ":started"), at)));
                    long delay = drawn(stage.delayDeciles(), packet.key, 300 + s);
                    agenda.add(new Pending(workingTime.nextWorkingInstant(at.plusSeconds(delay)), counter[0]++,
                            Kind.FINISH, packet, next.step(), 0));
                    continue;
                }
                queues.get(s).add(packet);
            } else if (next.kind() == Kind.FINISH) {
                if (!holding) {
                    busy[s]--;
                }
                BigDecimal stonesIn = packet.stones;
                BigDecimal stonesOut = lost(stonesIn, stage.stoneYield(), packet.key, 400 + s);
                BigDecimal caratsOut = packet.carats.multiply(stage.caratYield()).setScale(4, RoundingMode.HALF_EVEN);
                packet.stones = stonesOut;
                packet.carats = caratsOut;
                String key = "event:" + packet.lot + ":" + packet.number + ":" + s;
                UUID completionId = context.id(key + ":completed");
                Long work = holding ? null : next.workSeconds();
                int step = next.step();
                boolean last = step == packet.path.size() - 1;
                UUID nextStage = last ? null : packet.path.get(step + 1).id();

                // One completion is first recorded a stone too high, then corrected an hour later.
                boolean miscount = !miscountRecorded && stage.code().equals("SAWING")
                        && stonesOut.compareTo(stonesIn) < 0;
                BigDecimal recordedStones = miscount ? stonesOut.add(BigDecimal.ONE) : stonesOut;
                recorded.add(new Recorded(at, counter[0]++, () -> eventAppendService.stageCompleted(packet.id,
                        null, at, recordedStones, caratsOut, work, nextStage, Map.of(), completionId, at)));
                if (miscount) {
                    miscountRecorded = true;
                    Instant correctedAt = workingTime.addWorkingSeconds(at, 3_600);
                    recorded.add(new Recorded(correctedAt, counter[0]++, () -> eventAppendService.correct(
                            completionId, stonesOut, null, null, false,
                            Map.of("reason", "Recount after sawing (synthetic)"),
                            context.id(key + ":correction"), correctedAt)));
                }

                if (!last) {
                    // After work, the packet stays a while before it is moved on; held packets move at once.
                    long handling = holding ? 0 : drawn(stage.handlingDeciles(), packet.key, 200 + s);
                    agenda.add(new Pending(workingTime.nextWorkingInstant(at.plusSeconds(handling)), counter[0]++,
                            Kind.ARRIVE, packet, step + 1, 0));
                } else if (unfinished.merge(packet.lot, -1, Integer::sum) == 0 && finishedLots++ % 3 != 0) {
                    // Most finished lots are dispatched two working hours later; the first of every three
                    // to finish still waits.
                    Instant dispatchedAt = workingTime.addWorkingSeconds(at, 7_200);
                    UUID orderId = context.id("order:" + packet.lot);
                    recorded.add(new Recorded(dispatchedAt, counter[0]++, () -> eventAppendService.orderDispatched(
                            orderId, dispatchedAt, Map.of(), context.id("event:" + packet.lot + ":dispatched"),
                            dispatchedAt)));
                }
                if (holding) {
                    continue;
                }
            }

            // Start whatever can start at this stage now.
            Instant startAt = workingTime.nextWorkingInstant(at);
            if (startAt.isAfter(at)) {
                agenda.add(new Pending(startAt, counter[0]++, Kind.RECHECK, null, s, 0));
                continue;
            }
            int slots = slotsAt(stage, pools, windows, at);
            Deque<Packet> queue = queues.get(s);
            while (!queue.isEmpty() && busy[s] < slots) {
                Packet starting = queue.poll();
                busy[s]++;
                String key = "event:" + starting.lot + ":" + starting.number + ":" + s;
                recorded.add(new Recorded(at, counter[0]++, () -> eventAppendService.stageStarted(starting.id, null,
                        at, Map.of(), context.id(key + ":started"), at)));
                long nominal = stage.setupSeconds() + starting.stones.multiply(BigDecimal.valueOf(3_600))
                        .divide(stage.stonesPerHour(), 0, RoundingMode.CEILING).longValueExact();
                // Recorded work runs between 8% faster and 15% slower than the configured rate.
                long variation = 92 + DemoProductionPlan.mix(starting.key, 500 + s) % 24;
                long work = nominal * variation / 100;
                int step = starting.path.indexOf(stage);
                agenda.add(new Pending(workingTime.addWorkingSeconds(at, work), counter[0]++, Kind.FINISH,
                        starting, step, work));
            }
        }

        // Lots first, so that every packet exists before its events are written.
        sinceClear = 0;
        for (Lot lot : lots) {
            createLot(context, lot);
            written();
        }
        recorded.stream()
                .filter(entry -> !entry.at().isAfter(context.seededAt()))
                .sorted(Comparator.comparing(Recorded::at).thenComparingLong(Recorded::n))
                .forEach(entry -> {
                    entry.write().run();
                    written();
                });
    }

    /** Lots placed at a steady rhythm from the anchor until the seeding instant. */
    private static List<Lot> planLots(DemoSeedContext context, WorkingTime workingTime) {
        CalibratedDemoGenerator.Plan plan = CalibratedDemoGenerator.plan();
        List<Lot> lots = new ArrayList<>();
        int key = 0;
        for (int i = 1; ; i++) {
            Instant placedAt = workingTime.addWorkingSeconds(context.anchor(), (i - 1) * SECONDS_BETWEEN_LOTS);
            if (placedAt.isAfter(context.seededAt())) {
                return lots;
            }
            List<Packet> packets = new ArrayList<>();
            int count = i % 2 == 0 ? 3 : 2;
            for (int j = 1; j <= count; j++) {
                key++;
                int stones = DemoProductionPlan.stonesOf(key);
                BigDecimal carats = DemoProductionPlan.caratsPerStone(key).multiply(BigDecimal.valueOf(stones))
                        .setScale(4, RoundingMode.HALF_EVEN);
                packets.add(new Packet(i, j, key, DemoProductionPlan.routeOf(key, plan.routeShares()), stones,
                        carats));
            }
            lots.add(new Lot(i, context.id("order:" + i), placedAt, packets));
        }
    }

    /** The route's steps, each optional one taken by its share of packets (independently of the engine). */
    private static List<StageView> pathOf(Packet packet, RouteView route, List<StageView> stages,
            Map<UUID, Integer> stageIndex) {
        List<StageView> path = new ArrayList<>();
        for (RouteStepView step : route.steps()) {
            if (DemoProductionPlan.mix(packet.key, 100 + step.sequence()) % 100 < step.visitPercent()) {
                path.add(stages.get(stageIndex.get(step.stageId())));
            }
        }
        return path;
    }

    /** A duration in the measured distribution: a tenth chosen by the packet, and a point within it. */
    private static long drawn(List<Long> deciles, int key, long salt) {
        if (deciles == null || deciles.isEmpty()) {
            return 0;
        }
        long u = DemoProductionPlan.mix(key, salt) % 1000;
        int tenth = (int) (u / 100);
        double within = (u % 100) / 100.0;
        long low = deciles.get(tenth);
        long high = deciles.get(Math.min(tenth + 1, deciles.size() - 1));
        return low + Math.round((high - low) * within);
    }

    /** Whole stones out: the expected share kept, rounded up or down by the packet so losses average out. */
    private static BigDecimal lost(BigDecimal stonesIn, BigDecimal yield, int key, long salt) {
        double expected = stonesIn.doubleValue() * yield.doubleValue();
        double u = (DemoProductionPlan.mix(key, salt) % 1000) / 1000.0;
        long out = Math.min(stonesIn.longValue(), (long) Math.floor(expected + u));
        return BigDecimal.valueOf(out).setScale(4);
    }

    private void createLot(DemoSeedContext context, Lot lot) {
        int i = lot.index();
        var response = orderService.create(
                new CreateOrderRequest(context.factoryId(), "SDW-%04d".formatted(i),
                        "Demo buyer " + CUSTOMERS.charAt(i % CUSTOMERS.length()),
                        1 + Math.floorMod(i * 7, 5),
                        lot.placedAt().plus(Duration.ofDays(28 + i % 8)),
                        lot.placedAt(),
                        lot.packets().stream()
                                .map(packet -> new CreateBatchRequest(packet.initialStones, packet.initialCarats,
                                        packet.route))
                                .toList()),
                lot.id(), lot.placedAt());
        for (Packet packet : lot.packets()) {
            packet.id = com.realitycheck.common.domain.Ids.child(response.id(), "batch:" + packet.number);
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
