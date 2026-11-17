---
inclusion: always
---

# RealityCheck — Simulation Rules

These rules define the engine in `com.realitycheck.simulation.engine`. They are the contract the tests check.
Changing any rule that alters output requires bumping `SimulationEngine.ALGORITHM_VERSION`.

## 1. Determinism

- The engine is a pure function: `SimulationResult run(SimulationInput)`. Same input and algorithm version
  give a byte-identical result.
- No wall-clock reads, no randomness, no threads, no I/O, no floating point inside the engine.
- No iteration over `HashMap` or `HashSet` where order can affect output. Use lists, `TreeMap`, or
  `LinkedHashMap` built in a defined order.
- The engine never sees JPA entities. An adapter in `simulation` converts the stored snapshot to
  `SimulationInput` and sorts every collection by a stable key first.

## 2. Time

- Simulation time is whole **seconds** as a `long` (epoch seconds, UTC). Durations are whole seconds.
- A computed duration that is not a whole number of seconds is rounded **up**.
- The factory time zone is used only to turn calendar shifts into UTC working intervals.

## 3. Event ordering

Events are taken from a priority queue ordered by:

1. timestamp (ascending)
2. event-type priority (ascending): `RESOURCE_CAPACITY_CHANGE` 0, `STAGE_COMPLETED` 1, `SHIFT_START` 2,
   `JOB_ARRIVAL` 3, `DISPATCH_ATTEMPT` 4
3. sequence number (ascending), assigned when the event is scheduled

Completions come before arrivals at the same instant so that released resources are visible to waiting jobs.
The sequence number makes ordering total; two events never compare equal.

## 4. Units

| Quantity | Unit | Type |
|---|---|---|
| Stones | count, may be fractional inside the engine | `BigDecimal`, scale 4 |
| Carats | carat | `BigDecimal`, scale 4 |
| Processing rate | stones per hour **per processing slot** | `BigDecimal` |
| Setup | seconds per job per stage | `long` |
| Cost | currency units per hour per resource unit | `BigDecimal`, scale 2 for totals |
| Yield | fraction in (0, 1] | `BigDecimal`, scale 6 |

A **processing slot** is one complete set of a stage's requirements, for example one polishing wheel and two
polishers. The rate belongs to the slot, not to a machine or a person.

## 5. Jobs and resource allocation

- The unit of work is a **job**: one batch at one stage. A job occupies exactly one slot. Batches are not
  split or merged. More slots let more jobs run at once; they do not make a single job faster.
- A stage with no requirements has unlimited slots.
- **Routes (spec 11).** A batch with a route visits only its route's stages, in the route's order, and finishes
  at the route's last stage; a batch without one visits every stage in sequence. A route step below 100%
  is visited by the batches whose fixed hash of (batch id, stage) falls below that share; no randomness. A
  batch already at a stage when the run starts always stays on that stage. The run's output is what each
  finished batch produced at the end of its own path.
- **Holding stages (spec 11).** A stage of kind PARKING, WAITING or JOB_WORK uses no capacity and has no
  queue: a batch arriving there is held for one of the stage's ten measured delays (calendar seconds,
  nights and closed days included), chosen by a fixed hash of batch and stage, counted from when it
  entered the stage (before the fork for work already there) and never ending before the moment it is
  scheduled. PARKING is deliberate storage and is left out of manufacturing lead time; WAITING (such as
  waiting for dispatch) and JOB_WORK (outside job workers) count. Holding stages cost nothing and cannot
  require pools.
- **Handling delay (spec 11).** A working stage may have ten handling durations. After processing, its units
  are released at once and the batch stays at the stage for one of them (calendar seconds, chosen by the
  same kind of fixed hash), then moves on or finishes. Handling uses no capacity and does not make the stage
  active for the bottleneck signals; it counts in manufacturing lead time. Changing a pool's capacity does
  not change handling: more people help only where a stage is near capacity.
- **Routes in recorded history (spec 11).** Routes are stored on the workflow; a batch's `routeCode` is fixed
  when its order is created. Its recorded history follows the route: after a stage it is queued at a later
  step, skipping only optional ones, and it finishes at the route's last step. Snapshots carry the routes
  and each batch's route, so the engine continues each batch on the route it was recorded on.
- **Stage delay changes (spec 11).** `STAGE_DELAY` scales a stage's measured times (holding delays, or
  handling at a working stage) by a percentage, to whole seconds. It is the only change that alters
  measured times; capacity changes never do.
- Allocation is **atomic**: a job starts only if every required pool has enough free units at that instant.
  All units are taken together or none are. A job never holds part of its requirements while waiting.
- Processing is **non-preemptive**. Once started, a job keeps its units until it completes, including across
  breaks and overnight. Units held by a paused job count as allocated, not as busy.
- All units are released at `STAGE_COMPLETED`. At the end of a run every pool's allocated count is zero.
- Dispatch order within a stage queue: order priority (higher first), then order due date (earlier first),
  then queue-entry time, then batch id. Stages are scanned for dispatch in descending sequence so downstream
  work is served first when pools are shared.
- Pool capacity at an instant is `base capacity + scenario changes + availability windows`, floored at zero.
  A partial capacity reduction never interrupts or pauses running jobs; the pool stays over-committed until
  units are released, and its result carries `overcommitted: true`. A pool at **zero** capacity (full
  downtime) pauses the jobs that hold its units, as rule 6 describes.
- Validation rejects, before the run: negative capacity, a requirement that references a missing pool, a
  requirement larger than the pool's maximum capacity over the horizon (the stage could never run), a rate or
  yield that is not positive, a yield above 1, and an empty calendar when there is work to do.

## 6. Processing time

```
workSeconds    = setupSeconds + ceil(inputStones * 3600 / stonesPerHourPerSlot)
completionTime = the instant at which workSeconds of working time have elapsed since start
```

Working time for a job is the factory calendar minus every interval in which a pool it holds has zero
capacity. `completionTime` is found by walking working intervals; it is never `start + workSeconds` unless the
whole job fits inside one interval. A job may start only inside a working interval.

Machine-hours and elapsed time are different metrics. Four slots finishing four jobs in two hours is two
elapsed hours and eight slot-hours.

## 7. Calendar

- A factory has weekly shifts (day of week, local start, local end) and breaks inside shifts. Days without a
  shift are closed.
- Shifts are expanded into UTC half-open intervals `[start, end)` using the factory time zone. Breaks are
  subtracted. Overlapping intervals are merged.
- Resource downtime windows reduce pool capacity for their duration.
- The demo calendar is Monday to Saturday, 09:00 to 18:00, with a 13:00 to 14:00 break, Sunday closed.

## 8. Yield

At stage completion:

```
outputStones = inputStones * stoneYield      (scale 4, HALF_EVEN)
outputCarats = inputCarats * caratYield      (scale 4, HALF_EVEN)
```

Stones stay fractional through the run. They are **expected** quantities. There is no integer truncation
between stages. Whole-stone figures are produced only for display, by rounding the final value HALF_EVEN, and
the unrounded value is kept in the result. Conservation holds at every stage:
`input = output + loss`, exactly.

## 9. Work in progress at the fork

A batch that has `STAGE_STARTED` without `STAGE_COMPLETED` at the fork timestamp enters the simulation as an
in-progress job that already holds its units. Its remaining work is `workSeconds` minus the working seconds
elapsed between its recorded start and the fork, floored at zero. Batches queued at the fork enter that
stage's queue with their recorded queue-entry time. A stage recorded as completed for a batch is never
simulated again.

## 10. Termination

The run ends when no events remain, or at the first of two limits: `maxSimulatedSeconds` (default 180 days
past the fork) and `maxEvents` (default 1,000,000). Reaching a limit produces a result with
`terminationReason` of `HORIZON_REACHED` or `EVENT_LIMIT_REACHED` and the unfinished jobs listed. It is not an
exception. A queue of jobs that can never start because capacity is zero for the rest of the horizon ends as
`HORIZON_REACHED`.

## 11. Metrics

Let `T0` be the fork timestamp and `T1` the timestamp of the last completion.

| Metric | Definition |
|---|---|
| Elapsed completion time | `T1 - T0`, in seconds. Calendar time, includes nights and closed days. |
| Processing time | Sum over jobs of working seconds spent on setup and processing. |
| Waiting time | Sum over jobs of **working** seconds between `max(queue entry, T0)` and start. Waiting before the fork is history, not simulation; the recorded entry time is used only for dispatch order. Closed time is reported separately as calendar waiting. |
| Busy unit-seconds (pool) | Integral over working time of units actively processing. |
| Available unit-seconds (pool) | Integral over `[T0, T1]` of capacity during working time. |
| Utilization (pool) | `busy / available`. Reported as `null` with reason `NO_AVAILABLE_TIME` when available is zero. Never divided by zero. Capped at 1; an over-committed pool is flagged rather than reported above 1. |
| Idle unit-seconds | `available - busy`. |
| Machine and employee utilization | Capacity-weighted across pools of that kind: `sum(busy) / sum(available)`. |
| Stage duration | Mean and maximum working seconds from queue entry to completion for jobs at that stage. |
| Queue length | Time-weighted mean over working time, and maximum, per stage. |
| Throughput | Output stones of the final stage divided by elapsed working hours in `[T0, T1]`. Also reported per stage. |
| Operating cost | Employee pools: capacity x working hours in `[T0, T1]` x hourly cost (people are paid when idle). Machine pools: busy unit-hours x hourly cost. Plus each stage's fixed cost per job. Reported in total and by component. |
| Overall yield | Final output stones / initial input stones, and the same for carats. |
| Orders completed | Orders whose every batch finished the final stage. |
| Orders delayed | Completed orders with completion after the due date, plus unfinished orders whose due date is before the end of the run. |
| On-time delivery | `on-time completed orders / orders with a due date`. `null` when there are none. |

Scenarios of different lengths accrue cost over different horizons. The result therefore also reports cost per
output stone, and the comparison view shows both.

## 12. Bottleneck detection

Each stage receives a score from 0 to 1, a weighted sum of normalised signals:

| Signal | Default weight |
|---|---|
| Utilization of the stage's most constrained pool | 0.35 |
| Mean working wait at the stage, relative to the largest across stages | 0.30 |
| Time-weighted mean queue length, relative to the largest | 0.20 |
| Saturation: of the working time the stage had work (queued or running), the share with a non-empty queue and zero free slots | 0.15 |

The stage with the highest score is the primary bottleneck if its score is at least 0.5 **and its saturation
is at least 0.5**. Otherwise the run reports `NO_SUSTAINED_QUEUE` (the top stage's queue was brief relative to
its activity) or `NO_CLEAR_BOTTLENECK` (score below threshold); `NO_WORK` when nothing ran.

Saturation, not whole-run pool utilization, is the gate. Wait and queue are relative to the worst stage, so
some stage always scores 1 on both even when its wait was trivial; saturation is absolute and separates a
sustained queue from a brief one. Whole-run utilization cannot do that job: every run has a start-up and a
tail in which stages idle for lack of input, so a stage that was the binding constraint throughout typically
shows 0.6 to 0.8 utilization over the whole run. (The first design gated on utilization at least 0.85; it named
no bottleneck on the demonstration factory even when polishing was plainly the constraint.) Weights and thresholds are
configuration. The result always includes every stage's raw signal values, the limiting pool, and a
machine-readable reason, so the UI and the AI layer can show why. A long-running stage with spare capacity and
no queue is not a bottleneck.

Sensitivity analysis (later): re-run with one extra unit on each constrained pool and report the change in
completion time and throughput.

## 13. Properties the tests must hold

1. Identical input gives identical output.
2. No orders gives zero processing, zero waiting and zero cost from machines.
3. A stage whose pool has zero capacity processes nothing.
4. No batch completes the same stage twice; stages are completed in the order of its path (sequence order
   without a route). A route over every stage at 100% gives exactly the result of no route.
5. Free units never go below zero and allocated never exceeds what was granted; all units are released at the end.
6. Stone and carat conservation holds at every stage.
7. Utilization is in `[0, 1]` or `null`.
8. The run terminates within the configured limits for any valid input.
9. Removing units from a constrained pool never reduces total working wait in the single-stage, equal-priority case.
10. Adding units to a pool never increases completion time **in the single-stage case with identical job
    order**.

Property 10 is deliberately restricted. With non-preemptive priority dispatch across several stages that
share pools, adding capacity can occasionally lengthen the schedule (a known scheduling anomaly). The engine
reports what the dispatch rules produce; it does not hide such results. The multi-stage monotonicity check is
a reported statistic over generated cases, not an assertion.
