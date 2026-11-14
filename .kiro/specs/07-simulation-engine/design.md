# 07 Simulation Engine — Design

`.kiro/steering/simulation-rules.md` is the contract. Section numbers below ("rule 6") refer to it.

# Part A — The engine

Package `com.realitycheck.simulation.engine`. JDK only. Everything is a record, a final class or a sealed
interface. Entry point:

```java
public final class SimulationEngine {
    public static final String ALGORITHM_VERSION = "1.0.0";
    public SimulationResult run(SimulationInput input);   // throws InvalidSimulationInputException only
}
```

## A.1 Classes and responsibilities

| Class | Kind | Responsibility |
|---|---|---|
| `SimulationEngine` | final class | Validates input, builds context and state, runs the event loop, hands the final state to the metrics calculator. Holds `ALGORITHM_VERSION`. Stateless; safe to share. |
| `SimulationInput` | record | The complete immutable input (A.2). |
| `InputValidator` | final class | Checks rule 5's validation list; collects all problems into one `InvalidSimulationInputException`. |
| `SimulationContext` | final class | Read-only lookups derived once from the input: stages by sequence, pools by id (`TreeMap`), orders by id, `WorkingCalendar`, per-pool `CapacityProfile`, limits, bottleneck config. |
| `SimulationClock` | final class | Holds `now` as epoch seconds. `advanceTo(t)` rejects `t < now`. The only source of time. |
| `SimulationEvent` | record | `(long time, EventType type, long seq, UUID batchId, UUID poolId)`. `EventType` carries its priority. `Comparable` by (time, priority, seq). |
| `SimulationEventQueue` | final class | Wraps `PriorityQueue<SimulationEvent>`; assigns `seq` from a counter in `schedule(time, type, …)`. |
| `SimulationState` | final class | All mutable run state: pools, per-stage queues, running jobs, completed `StageExecution`s, accumulators, trace. Created per run, never shared. |
| `ResourcePool` | final class | One pool's live state: `capacity`, `allocated`. `free() = max(0, capacity - allocated)`. `allocate(n)`, `release(n)`, `setCapacity(c)`. Throws `IllegalStateException` on release of more than allocated. |
| `ResourceAllocation` | record | `(UUID poolId, int units)`. A job holds a list of these, all or none. |
| `ProductionBatch` | record | Immutable batch identity inside the engine: `(id, orderId, priority, dueTime, initialStones, initialCarats)`. |
| `ProductionJob` | final class | One batch at one stage: `batch`, `stageSequence`, `inputStones`, `inputCarats`, `queueEntryTime`, `startTime`, `workSeconds`, `completionTime`, `allocations`. State `QUEUED → RUNNING → DONE`. |
| `StageExecution` | record | The immutable record of a finished job: batch, stage, queue entry, start, completion, working wait seconds, calendar wait seconds, work seconds, input and output stones and carats, allocations. Input to metrics. |
| `StageScheduler` | final class | Dispatch: scans stages in descending sequence, orders each queue, performs atomic allocation, computes completion time, schedules `STAGE_COMPLETED`. |
| `WorkingCalendar` | final class | Expands weekly shifts into UTC working intervals on demand; answers `isWorking(t)`, `nextWorkingStart(t)`, `workingSecondsBetween(a, b)`, `addWorkingSeconds(t, n)`. |
| `CapacityProfile` | final class | A pool's capacity over time as sorted segments from base capacity and windows; `capacityAt(t)`, `maxCapacity()`, `zeroIntervals()`, `boundariesAfter(t)`. |
| `IntervalSet` | final class | Sorted, merged half-open `long` intervals with `union`, `subtract`, `intersect`. Used by the calendar and for job working time. |
| `SimulationMetricsCalculator` | final class | Pure function of context + final state → summary, stage, pool and order results (A.6). |
| `BottleneckAnalyzer` | final class | Signals, normalisation, score, decision, reason codes (A.7). |
| `SimulationResult` | record | Everything the engine returns (A.3). |

## A.2 Input

```java
record SimulationInput(
    long forkTime,                       // epoch seconds, T0
    CalendarSpec calendar,
    List<StageSpec> stages,
    List<PoolSpec> pools,
    List<OrderSpec> orders,
    List<BatchSpec> batches,
    SimulationLimits limits,
    BottleneckConfig bottleneck) {}

record CalendarSpec(String zoneId, List<ShiftSpec> shifts) {}
record ShiftSpec(int dayOfWeek, int startSecondOfDay, int endSecondOfDay, List<BreakSpec> breaks) {}
record BreakSpec(int startSecondOfDay, int endSecondOfDay) {}

record StageSpec(UUID id, String code, int sequence, BigDecimal stonesPerHourPerSlot, long setupSeconds,
                 BigDecimal stoneYield, BigDecimal caratYield, BigDecimal fixedCostPerJob,
                 List<RequirementSpec> requirements) {}
record RequirementSpec(UUID poolId, int unitsPerSlot) {}

enum ResourceKind { MACHINE, EMPLOYEE }
record PoolSpec(UUID id, ResourceKind kind, String name, int baseCapacity, BigDecimal hourlyCost,
                List<CapacityWindow> windows) {}
record CapacityWindow(long from, long to, int delta) {}       // [from, to)

record OrderSpec(UUID id, String orderNumber, int priority, Long dueTime, int completedBatchCount) {}

enum BatchEntryState { QUEUED, IN_PROGRESS, FUTURE_ARRIVAL }
record BatchSpec(UUID id, UUID orderId, BatchEntryState state, int stageSequence,
                 BigDecimal stones, BigDecimal carats,       // quantities entering stageSequence
                 Long queueEntryTime, Long startTime, Long arrivalTime) {}

record SimulationLimits(long maxSimulatedSeconds, long maxEvents) {}       // defaults 15_552_000 and 1_000_000
record BottleneckConfig(BigDecimal wUtilization, BigDecimal wWait, BigDecimal wQueue, BigDecimal wBlocked,
                        BigDecimal scoreThreshold, BigDecimal utilizationThreshold) {}
                        // defaults 0.35, 0.30, 0.20, 0.15, 0.50, 0.85
```

On entry the engine copies and sorts: stages by `sequence`, pools by `id`, orders by `id`, batches by `id`,
requirements by `poolId`, windows by `(from, to, delta)`, shifts by `(dayOfWeek, start)`. This is what makes
requirement 1.5 hold.

## A.3 Result

```java
enum TerminationReason { COMPLETED, HORIZON_REACHED, EVENT_LIMIT_REACHED }

record SimulationResult(
    String algorithmVersion, long forkTime, long endTime /* T1 */, TerminationReason terminationReason,
    long eventsProcessed,
    SummaryMetrics summary, List<StageResult> stages, List<PoolResult> pools, List<OrderResult> orders,
    BottleneckResult bottleneck, List<UnfinishedJob> unfinished, List<TraceEvent> trace) {}

record SummaryMetrics(
    long elapsedSeconds, long workingSecondsInWindow,
    long processingSeconds, long waitingWorkingSeconds, long waitingCalendarSeconds,
    BigDecimal inputStones, BigDecimal outputStones, BigDecimal outputStonesRounded,
    BigDecimal inputCarats, BigDecimal outputCarats,
    BigDecimal stoneYield, BigDecimal caratYield,                 // null when input is zero
    BigDecimal throughputStonesPerHour,                           // null when no working time
    BigDecimal machineUtilization, BigDecimal employeeUtilization,// null when no available time
    CostBreakdown cost,
    int ordersTotal, int ordersCompleted, int ordersDelayed, int ordersWithDueDate,
    BigDecimal onTimeDeliveryRate) {}                             // null when no order has a due date

record CostBreakdown(BigDecimal employee, BigDecimal machine, BigDecimal fixed, BigDecimal total,
                     BigDecimal perOutputStone) {}                // perOutputStone null when output is zero

record StageResult(
    UUID stageId, String code, int sequence, int jobsCompleted,
    BigDecimal inputStones, BigDecimal outputStones, BigDecimal lossStones,
    BigDecimal inputCarats, BigDecimal outputCarats, BigDecimal lossCarats,
    long processingSeconds, long waitingWorkingSeconds, long waitingCalendarSeconds,
    BigDecimal meanWaitWorkingSeconds, BigDecimal meanStageSeconds, long maxStageSeconds,
    BigDecimal meanQueueLength, int maxQueueLength,
    BigDecimal throughputStonesPerHour, BigDecimal fixedCost,
    BottleneckSignals bottleneck) {}

record PoolResult(
    UUID poolId, ResourceKind kind, String name,
    long busyUnitSeconds, long availableUnitSeconds, long idleUnitSeconds,
    BigDecimal utilization, String utilizationNullReason /* NO_AVAILABLE_TIME or null */,
    boolean overcommitted, BigDecimal cost) {}

enum OrderOutcome { ON_TIME, LATE, UNFINISHED }
record OrderResult(UUID orderId, String orderNumber, Long completionTime, Long dueTime,
                   OrderOutcome outcome, boolean delayed) {}

record BottleneckSignals(
    UUID limitingPoolId, BigDecimal limitingPoolUtilization,
    BigDecimal utilizationSignal, BigDecimal waitSignal, BigDecimal queueSignal, BigDecimal blockedSignal,
    BigDecimal blockedShare, BigDecimal score) {}

record BottleneckResult(UUID primaryStageId /* null = no clear bottleneck */, String reasonCode,
                        BottleneckConfig configUsed) {}

record UnfinishedJob(UUID batchId, UUID orderId, int stageSequence, String state /* QUEUED or RUNNING */) {}

enum TraceType { JOB_QUEUED, JOB_STARTED, JOB_COMPLETED, CAPACITY_CHANGED, ORDER_COMPLETED }
record TraceEvent(long seq, long time, TraceType type, UUID batchId, UUID orderId, UUID stageId, UUID poolId,
                  BigDecimal stones, BigDecimal carats, Integer capacity) {}
```

Scales: quantities 4, yields and ratios (utilization, rates, signals, score) 6, means 4, money 2. All
divisions use `RoundingMode.HALF_EVEN` with the stated scale.

## A.4 Calendar and working time

`WorkingCalendar` expands the weekly pattern one local day at a time and caches the result:

```
intervalsForLocalDate(date):
  for shift in shifts where shift.dayOfWeek == date.dayOfWeek (ordered by start):
     s = ZonedDateTime.of(date, LocalTime.ofSecondOfDay(shift.start), zone).toEpochSecond()
     e = ZonedDateTime.of(date, LocalTime.ofSecondOfDay(shift.end),   zone).toEpochSecond()
     set = set ∪ [s, e)
     for break in shift.breaks: set = set \ [break start, break end) converted the same way
  return set (merged, sorted)
```

`ZonedDateTime.of` resolves daylight-saving gaps and overlaps with the JDK's documented rule, which is
deterministic. A shift whose end is `86400` (24:00) is converted as the next local midnight.

Operations, all in whole seconds:

- `isWorking(t)`: `t` lies in an interval.
- `nextWorkingStart(t)`: `t` if working; else the start of the first interval after `t`; searches forward
  day by day up to the horizon; `Long.MAX_VALUE` if none.
- `workingSecondsBetween(a, b)`: total overlap of `[a, b)` with working intervals.
- `intervalsBetween(a, b)`: the working intervals clipped to `[a, b)`.

**Job working time.** For a job holding pools `P`, its working set is

```
W(job) = calendar \ ⋃ { zeroIntervals(p) : p ∈ P }
```

where `zeroIntervals(p)` are the periods in which the pool's capacity is zero. This is how rule 6
("intersection of the factory calendar and the availability of every pool it holds") and rule 5 ("a capacity
reduction never interrupts running jobs") fit together: a **partial** reduction leaves running jobs working;
a pool at **zero** capacity is unavailable and pauses the jobs that hold it. Because windows are part of the
input, `W(job)` is known when the job starts.

**Completion time (interval walking).**

```
completionTime(start, workSeconds, W):
  if workSeconds == 0: return start
  remaining = workSeconds
  t = start
  for interval [s, e) in W with e > t, ascending:
      from = max(s, t)
      len  = e - from
      if remaining <= len: return from + remaining
      remaining -= len
  return Long.MAX_VALUE        // never completes within any horizon
```

A completion time of `Long.MAX_VALUE` is scheduled as an event beyond the horizon, which ends the run with
`HORIZON_REACHED` (rule 10).

## A.5 The event loop

```
run(input):
  problems = InputValidator.validate(input); if any: throw InvalidSimulationInputException(problems)
  ctx   = SimulationContext.of(input)                    // sorts everything
  state = new SimulationState(ctx)                       // pools at capacityAt(T0), empty queues
  clock = new SimulationClock(T0)
  queue = new SimulationEventQueue()
  horizonEnd = T0 + limits.maxSimulatedSeconds

  // --- initialisation, in this order ---
  for pool in ctx.pools (by id):
      for boundary b in pool.capacityProfile.boundariesAfter(T0), b <= horizonEnd:
          queue.schedule(b, RESOURCE_CAPACITY_CHANGE, poolId)
  for batch in ctx.batches (by id):
      switch batch.state:
        IN_PROGRESS:
            job = running job at batch.stageSequence, startTime = batch.startTime
            allocate its units unconditionally (may over-commit the pool; rule 5)
            work      = workSeconds(stage, batch.stones)
            elapsed   = workingSeconds(W(job), batch.startTime, T0)
            remaining = max(0, work - elapsed)
            job.completionTime = completionTime(T0, remaining, W(job))
            job.simulatedWorkSeconds = remaining
            queue.schedule(job.completionTime, STAGE_COMPLETED, batchId)
        QUEUED:
            state.queue(batch.stageSequence).add(job with queueEntryTime = batch.queueEntryTime)
            trace JOB_QUEUED at T0
        FUTURE_ARRIVAL:
            queue.schedule(max(batch.arrivalTime, T0), JOB_ARRIVAL, batchId)
  queue.schedule(T0, DISPATCH_ATTEMPT)
  scheduleNextShiftStart(T0)                             // only if unfinished work exists

  // --- main loop ---
  reason = COMPLETED
  while queue is not empty:
      ev = queue.peek()
      if ev.time > horizonEnd:                 reason = HORIZON_REACHED;     break
      if state.eventsProcessed == maxEvents:   reason = EVENT_LIMIT_REACHED; break
      queue.poll(); state.eventsProcessed++
      state.accrue(clock.now, ev.time)         // A.6: queue-length and blocked integrals over working time
      clock.advanceTo(ev.time)

      switch ev.type:
        RESOURCE_CAPACITY_CHANGE:
            pool.setCapacity(profile.capacityAt(now)); trace CAPACITY_CHANGED
            requestDispatch()
        STAGE_COMPLETED:
            job = state.running.remove(ev.batchId)
            release every allocation of job
            out = yield(job)                                   // rule 8
            record StageExecution; trace JOB_COMPLETED
            if job.stage is last:
                state.markBatchFinished(job.batch, now)
                if all batches of the order are finished: record order completion; trace ORDER_COMPLETED
            else:
                state.queue(job.stage + 1).add(new job(batch, inputs = out, queueEntryTime = now))
                trace JOB_QUEUED
            requestDispatch()
        SHIFT_START:
            scheduleNextShiftStart(now)
            requestDispatch()
        JOB_ARRIVAL:
            state.queue(1).add(new job(batch, queueEntryTime = now)); trace JOB_QUEUED
            requestDispatch()
        DISPATCH_ATTEMPT:
            state.dispatchPending = false
            StageScheduler.dispatch(now)

  T1 = time of the last STAGE_COMPLETED processed, or T0 if none
  return SimulationMetricsCalculator.calculate(ctx, state, T1, reason)
```

`requestDispatch()`: if `!state.dispatchPending`, schedule `DISPATCH_ATTEMPT` at `now` and set the flag.
Because `DISPATCH_ATTEMPT` has the lowest priority, exactly one dispatch runs per instant, after every
capacity change, completion, shift start and arrival at that instant. This is the mechanism behind rule 3's
"completions come before arrivals so released resources are visible".

`scheduleNextShiftStart(t)`: if any queue is non-empty or any `JOB_ARRIVAL` is still pending, schedule
`SHIFT_START` at the start of the first calendar interval strictly after `t`. When no work remains nothing is
scheduled, so the queue drains and the run ends. When work is stuck forever (zero capacity) shift starts keep
coming until one lies beyond the horizon, which yields `HORIZON_REACHED` (requirement 8.5).

**Dispatch.**

```
StageScheduler.dispatch(now):
  if !calendar.isWorking(now): return
  for stage in stages by sequence DESCENDING:
      q = state.queue(stage) sorted by (priority DESC, dueTime ASC nulls last, queueEntryTime ASC, batchId ASC)
      for job in q:
          if any required pool has capacityAt(now) == 0: break     // not a working instant for this stage
          if any required pool has free() < unitsPerSlot: break    // every job at a stage needs the same set
          // atomic: both checks passed for all pools before anything is taken
          for req in stage.requirements (by poolId): pool.allocate(req.unitsPerSlot)
          job.startTime      = now
          job.workSeconds    = stage.setupSeconds + ceilDiv(job.inputStones * 3600, stage.stonesPerHourPerSlot)
          job.completionTime = completionTime(now, job.workSeconds, W(job))
          q.remove(job); state.running.put(job.batchId, job)
          queue.schedule(job.completionTime, STAGE_COMPLETED, job.batchId); trace JOB_STARTED
```

`ceilDiv` on `BigDecimal`: `stones.multiply(3600).divide(rate, 0, RoundingMode.CEILING).longValueExact()`.
A stage with no requirements skips both pool checks, so all its queued jobs start.

The queue per stage is a `TreeSet<ProductionJob>` with the comparator above, so no re-sorting is needed.
The check-then-take inside one method with no intervening event is what makes allocation atomic; there is no
"reserved" state.

## A.6 Metrics

`T0 = forkTime`, `T1` as above, `Wk = calendar.workingSecondsBetween(T0, T1)`.

**Accrued during the loop** (`state.accrue(a, b)`, called before each event with `a = previous event time`):
with `w = calendar.workingSecondsBetween(a, b)`, for each stage
`queueIntegral += queueLength * w`, `maxQueue = max(maxQueue, queueLength)` (max is sampled after every
dispatch), and `blockedSeconds += w` if the queue is non-empty and the stage's free slots
(`min over requirements of floor(free / unitsPerSlot)`) are zero. State is constant between events, so the
integrals are exact.

**From `StageExecution` records**, per job with `q = max(queueEntryTime, T0)`:

| Quantity | Formula |
|---|---|
| working wait | `calendar.workingSecondsBetween(q, startTime)` |
| calendar wait | `startTime - q` |
| processing | `workSeconds` (for jobs in progress at the fork: the remaining seconds simulated) |
| stage duration | `calendar.workingSecondsBetween(q, completionTime)` |

Jobs in progress at the fork contribute zero wait. Queue-entry times before `T0` affect dispatch order but
waiting is measured from `T0`, because the metrics describe the simulated window `[T0, T1]`.

**Pools.**

```
busyUnitSeconds      = Σ over finished jobs holding the pool: units * processing seconds
                       + for jobs still running at the end: units * workingSeconds(W(job), start, endOfRun)
availableUnitSeconds = Σ over capacity segments [a,b) ∩ [T0,T1]: capacity * calendar.workingSecondsBetween(a,b)
utilization          = null (NO_AVAILABLE_TIME)            if available == 0
                     = min(1, busy / available)  scale 6   otherwise
overcommitted        = busy > available
idleUnitSeconds      = max(0, available - busy)
```

`busy > available` can only arise when a job in progress at the fork, or running when capacity is reduced,
holds units above the pool's capacity (rule 5). The ratio is capped at 1 as rule 11 requires and the
`overcommitted` flag records that the cap was applied.

Kind-level utilization: `Σ min(busy, available) / Σ available` over pools of that kind; `null` when the
denominator is zero.

**Cost** (money scale 2, each component rounded once, `total` = sum of rounded components):

```
employee = Σ employee pools: availableUnitSeconds * hourlyCost / 3600
machine  = Σ machine pools:  busyUnitSeconds      * hourlyCost / 3600
fixed    = Σ stages: jobsCompleted * fixedCostPerJob
perOutputStone = total / outputStones   (scale 4; null when outputStones == 0)
```

**Throughput.** Factory: `outputStones of the last stage * 3600 / Wk` (scale 4; `null` when `Wk == 0`).
Per stage: that stage's output stones over the same `Wk`.

**Yield.** `inputStones` = Σ over batches of the quantity each had on entry to the simulation;
`outputStones` = Σ final-stage outputs. `stoneYield = output / input` (scale 6; `null` when input is zero).
Note this is the yield over the simulated window; batches unfinished at the end lower it, and
`unfinished` says why.

**Orders.** An order is complete when `completedBatchCount + finished batches in the run` equals its batch
total and it has no unfinished batch. `completionTime` = latest final-stage completion among its batches.
`outcome`: `ON_TIME` if complete and (`dueTime` null or `completionTime <= dueTime`); `LATE` if complete and
after due; `UNFINISHED` otherwise. `delayed` = `LATE`, or `UNFINISHED` with `dueTime < endOfRun`.
`onTimeDeliveryRate` = orders with a due date and `ON_TIME` / orders with a due date.

## A.7 Bottleneck analysis

Per stage `s`:

```
limitingPool(s)     = required pool with the highest utilization (null counts as 0; ties → lowest pool id)
U(s)                = that utilization, or 0 when the stage has no requirements
waitMean(s)         = mean working wait of jobs started at s (0 when none)
queueMean(s)        = queueIntegral(s) / Wk     (0 when Wk == 0)
blockedShare(s)     = blockedSeconds(s) / Wk    (0 when Wk == 0)

utilizationSignal   = U(s)
waitSignal          = waitMean(s)  / max over stages of waitMean     (0 when the max is 0)
queueSignal         = queueMean(s) / max over stages of queueMean    (0 when the max is 0)
blockedSignal       = blockedShare(s)

score(s) = wUtilization*utilizationSignal + wWait*waitSignal + wQueue*queueSignal + wBlocked*blockedSignal
```

Decision: `top` = stage with the highest score (ties → lowest sequence).

| Condition | `primaryStageId` | `reasonCode` |
|---|---|---|
| `score(top) >= scoreThreshold` and `U(top) >= utilizationThreshold` | `top` | `CONSTRAINED_POOL_WITH_QUEUE` |
| `score(top) >= scoreThreshold` but `U(top) < utilizationThreshold` | null | `QUEUE_WITHOUT_SATURATED_POOL` |
| `score(top) < scoreThreshold` | null | `NO_CLEAR_BOTTLENECK` |
| no jobs processed | null | `NO_WORK` |

A long stage with free slots and no queue has `waitSignal`, `queueSignal` and `blockedSignal` near zero and at
most `0.35 * U` from utilization, which cannot reach 0.5; this is requirement 10.4. All raw and normalised
values are in `BottleneckSignals` so the UI and the AI layer can show the evidence.

## A.8 Engine error handling

- Invalid input → `InvalidSimulationInputException(List<Problem(code, message, subjectId)>)`.
- Limits → normal result with a non-`COMPLETED` `terminationReason`.
- Internal invariant violation (release more than allocated, time moving backwards, completing a batch at a
  stage twice) → `IllegalStateException`. These are bugs; Part B turns them into a `FAILED` run.

## A.9 Engine testing

Plain JUnit 5 and jqwik, no Spring. A small `InputBuilder` test fixture makes inputs readable.

Example-based (`SimulationEngineTest`, `WorkingCalendarTest`, `StageSchedulerTest`,
`SimulationMetricsCalculatorTest`, `BottleneckAnalyzerTest`, `InputValidatorTest`), each named after the rule
it checks:

- One job, one stage, hand-computed completion inside one shift; across a break; across a night; across Sunday.
- Rounding up of fractional seconds; setup added once per job.
- Atomic allocation: machine free but employees short → job does not start and the machine stays free.
- Two jobs, two slots → elapsed equals one job's time, slot-seconds equal two.
- Dispatch order by priority, due date, queue entry, batch id.
- Downstream-first scan when two stages share a pool.
- Same-instant completion and arrival: the arrival uses the released unit.
- Partial capacity reduction does not pause a running job; full downtime does.
- Work in progress at the fork: remaining work, zero remaining, held units, over-commit flag.
- Yield chain over eight stages with exact expected decimals; conservation.
- Limits: zero-capacity pool → `HORIZON_REACHED` with unfinished jobs; tiny `maxEvents` → `EVENT_LIMIT_REACHED`.
- Each validation code; multiple problems reported together.
- Metrics on a hand-worked three-stage example with every number asserted.
- Bottleneck: saturated stage with queue is primary; long stage with spare capacity is not; no work → `NO_WORK`.
- Calendar: time-zone conversion for `Asia/Kolkata`, a zone with daylight saving, 24:00 shift end, merge of overlapping shifts.

Property-based (`SimulationEngineProperties`), generators produce valid inputs (1-5 stages, 1-4 pools, 0-20
batches, small calendars, optional windows):

| # | Property (simulation-rules.md section 13) |
|---|---|
| 1 | `run(x).equals(run(x))`, and equal after shuffling every input list |
| 2 | no batches → zero processing, waiting and machine cost |
| 3 | a stage whose pool has zero capacity over the horizon completes no jobs |
| 4 | per batch, completed stage sequences are strictly increasing with no repeats |
| 5 | replaying the trace: allocated never negative, never above what was granted; all zero at the end when `COMPLETED` |
| 6 | `input = output + loss` per stage for stones and carats |
| 7 | every utilization is `null` or in [0, 1] |
| 8 | the run returns, with `eventsProcessed <= maxEvents` |
| 9 | single stage, equal priority, no due dates: removing a unit from the constrained pool never reduces total working wait |
| 10 | single stage, identical job order: adding a unit never increases `elapsedSeconds` |

`MultiStageMonotonicityReport` (a jqwik property with `@Report` statistics, no assertion): share of generated
multi-stage cases in which one added unit increased `elapsedSeconds`.

`EngineArchitectureTest` (ArchUnit): rule 1 of architecture.md, plus "no `double`, `float`, `Random`,
`System.currentTimeMillis`, `Instant.now`, `HashMap` iteration" checked by a source-level test that scans the
engine package for those tokens.

# Part B — Run execution

Package `com.realitycheck.simulation`.

## B.1 Components

| Class | Responsibility |
|---|---|
| `api.SimulationController` | `POST /api/scenarios/{id}/simulate`, `GET /api/scenarios/{id}/simulations`, `GET /api/simulations/{runId}` and `/results`, `/stages`, `/resources`, `/events`. |
| `api.*Response` | `RunSubmittedResponse`, `RunResponse`, `RunResultsResponse`, `StageResultResponse`, `ResourceResultResponse`, `SimulatedEventResponse`. |
| `service.SimulationSubmissionService` | Idempotency, reuse by hash, permit check, PENDING insert, hand-off after commit. |
| `service.SimulationRunExecutor` | Background execution: claim, run engine, persist, fail safely. |
| `service.SnapshotInputAdapter` | `Snapshot` (spec 06) → `SimulationInput`. Converts instants to epoch seconds and local times to seconds of day. No logic beyond mapping. |
| `service.SimulationResultWriter` | Maps `SimulationResult` to entities and the summary JSON; one transaction. |
| `service.SimulationQueryService` | Reads for the API; implements `SimulationResultsReader` used by `scenario` (comparison) and `ai`. |
| `service.StaleRunRecovery` | `ApplicationRunner` marking interrupted runs `FAILED`. |
| `config.SimulationExecutorConfig` | `ThreadPoolTaskExecutor` (`core=max=2`, `queueCapacity=8` by default) and a `Semaphore` with `max + queueCapacity` permits. Properties under `realitycheck.simulation.*`. |
| `domain.SimulationRun`, `SimulationStageResult`, `SimulationResourceResult`, `SimulationEventResult`, `RunStatus` | Entities and enum. |

`SimulationResultsReader` is declared in `simulation` and returns records; `scenario`'s comparison code
(spec 08) depends on it through an interface declared in `scenario` and implemented here, keeping the
dependency direction `simulation → scenario`.

## B.2 Data model

```sql
simulation_run (
  id                 uuid primary key,
  scenario_id        uuid not null references scenario(id),
  factory_id         uuid not null references factory(id),
  status             varchar(16) not null check (status in ('PENDING','RUNNING','COMPLETED','FAILED')),
  idempotency_key    varchar(100),
  fork_at            timestamptz not null,
  input_snapshot     jsonb not null,
  input_hash         char(64) not null,
  base_state_hash    char(64) not null,
  algorithm_version  varchar(20) not null,
  random_seed        bigint,                      -- always null in the deterministic MVP
  termination_reason varchar(24),
  simulated_end_at   timestamptz,                 -- T1
  events_processed   bigint,
  summary            jsonb,                       -- SummaryMetrics + orders[] + bottleneck + unfinished[]
  failure_message    varchar(500),
  submitted_at       timestamptz not null,
  started_at         timestamptz,
  finished_at        timestamptz,
  created_at         timestamptz not null,
  unique (scenario_id, idempotency_key)
)

simulation_stage_result (
  id uuid primary key,
  run_id uuid not null references simulation_run(id) on delete cascade,
  stage_id uuid not null, stage_code varchar(40) not null, stage_name varchar(120) not null, sequence integer not null,
  jobs_completed integer not null,
  input_stones numeric(14,4) not null, output_stones numeric(14,4) not null, loss_stones numeric(14,4) not null,
  input_carats numeric(14,4) not null, output_carats numeric(14,4) not null, loss_carats numeric(14,4) not null,
  processing_seconds bigint not null, waiting_working_seconds bigint not null, waiting_calendar_seconds bigint not null,
  mean_wait_working_seconds numeric(16,4) not null, mean_stage_seconds numeric(16,4) not null, max_stage_seconds bigint not null,
  mean_queue_length numeric(12,4) not null, max_queue_length integer not null,
  throughput_stones_per_hour numeric(14,4), fixed_cost numeric(16,2) not null,
  limiting_resource_id uuid, limiting_utilization numeric(7,6),
  utilization_signal numeric(7,6) not null, wait_signal numeric(7,6) not null,
  queue_signal numeric(7,6) not null, blocked_signal numeric(7,6) not null,
  blocked_share numeric(7,6) not null, bottleneck_score numeric(7,6) not null,
  primary_bottleneck boolean not null,
  created_at timestamptz not null,
  unique (run_id, sequence)
)

simulation_resource_result (
  id uuid primary key,
  run_id uuid not null references simulation_run(id) on delete cascade,
  resource_id uuid not null, kind varchar(16) not null, name varchar(120) not null, capacity integer not null,
  busy_unit_seconds bigint not null, available_unit_seconds bigint not null, idle_unit_seconds bigint not null,
  utilization numeric(7,6), utilization_null_reason varchar(32), overcommitted boolean not null,
  cost numeric(16,2) not null,
  created_at timestamptz not null,
  unique (run_id, resource_id)
)

simulation_event_result (
  id uuid primary key,
  run_id uuid not null references simulation_run(id) on delete cascade,
  seq bigint not null,
  occurred_at timestamptz not null,
  event_type varchar(24) not null,
  batch_id uuid, order_id uuid, stage_id uuid, resource_id uuid,
  stone_quantity numeric(14,4), carat_quantity numeric(14,4), capacity integer,
  unique (run_id, seq)
)
```

Indexes: `simulation_run(scenario_id, submitted_at desc)`, `simulation_run(scenario_id, input_hash)`,
`simulation_run(status) where status in ('PENDING','RUNNING')`, `simulation_event_result(run_id, stage_id)`.
Result tables store `stage_id` and `resource_id` without foreign keys and copy names from the snapshot:
results must stay readable and unchanged even if configuration is later edited. Result rows have no
`updated_at`; they are written once.

## B.3 Submission

```
submit(scenarioId, idempotencyKey?, force):
  scenario = load or 404
  if key present: existing = findByScenarioAndKey → return it (reused = true)
  assembled = ScenarioSnapshotService.assemble(scenarioId)          // 422 on validation errors
  if !force: done = latest COMPLETED run with same scenario and input_hash → return it (reused = true)
  if !permits.tryAcquire(): throw TooManyRequests (429, Retry-After: 5)
  try:
     tx: insert simulation_run(PENDING, snapshot, hashes, ALGORITHM_VERSION, submitted_at = clock.now)
         on unique violation of (scenario_id, idempotency_key): release permit; return the existing row
     after commit: executor.execute(() -> runExecutor.execute(runId))
  catch: release permit; rethrow
  return 202 { runId, status: PENDING, reused: false }
```

The permit is released in a `finally` at the end of `SimulationRunExecutor.execute`. Because permits equal
threads plus queue slots, the executor never rejects a task.

## B.4 Execution

```
execute(runId):
  try:
    tx1: claimed = UPDATE simulation_run SET status='RUNNING', started_at=:now WHERE id=:id AND status='PENDING'
         if claimed == 0: return                                    // already taken or recovered: no duplicate execution
    snapshot = read input_snapshot (only this column, only this row)
    input    = SnapshotInputAdapter.toInput(snapshot)
    result   = engine.run(input)                                    // outside any transaction
    tx2: SimulationResultWriter.write(runId, snapshot, result)      // stage, resource, event rows (JDBC batch),
                                                                    // summary, termination_reason, simulated_end_at,
                                                                    // status='COMPLETED', finished_at
  catch Throwable t:
    log.error("simulation failed runId={} scenarioId={}", …, t)     // full detail in the log only
    tx3: UPDATE … SET status='FAILED', failure_message=:safe, finished_at=:now WHERE id=:id AND status in ('PENDING','RUNNING')
  finally:
    permits.release()
```

`safe` message: for `InvalidSimulationInputException` the joined problem messages (these are user-facing by
design); for anything else "The simulation could not be completed. Reference: <runId>".
`tx2` is one `@Transactional` method, so a failure while writing event rows leaves no result rows and the run
ends `FAILED` (requirement 13.3).

A run that ends on a limit is still `COMPLETED` as an execution; its `termination_reason` tells the user the
simulation did not finish the work.

## B.5 API

`POST /api/scenarios/{id}/simulate?force=false` with optional header `Idempotency-Key` → 202

```json
{ "runId": "…", "scenarioId": "…", "status": "PENDING", "reused": false, "submittedAt": "…" }
```

`GET /api/simulations/{runId}`

```json
{ "runId": "…", "scenarioId": "…", "scenarioName": "Add two polishing machines", "status": "COMPLETED",
  "forkAt": "…", "algorithmVersion": "1.0.0", "inputHash": "…", "baseStateHash": "…",
  "terminationReason": "COMPLETED", "simulatedEndAt": "…", "eventsProcessed": 1840,
  "failureMessage": null, "submittedAt": "…", "startedAt": "…", "finishedAt": "…" }
```

`GET /api/simulations/{runId}/results` (409 unless `COMPLETED`)

```json
{ "runId": "…", "source": "SIMULATED", "algorithmVersion": "1.0.0", "currencyCode": "INR",
  "forkAt": "…", "simulatedEndAt": "…", "terminationReason": "COMPLETED",
  "summary": { "elapsedSeconds": 432000, "workingSecondsInWindow": 144000, "processingSeconds": 512000,
               "waitingWorkingSeconds": 96000, "waitingCalendarSeconds": 410000,
               "inputStones": "1820.0000", "outputStones": "1712.4480", "outputStonesRounded": "1712",
               "inputCarats": "2310.5000", "outputCarats": "1049.2210",
               "stoneYield": "0.940906", "caratYield": "0.454110",
               "throughputStonesPerHour": "42.8112",
               "machineUtilization": "0.713400", "employeeUtilization": "0.642100",
               "cost": { "employee": "182400.00", "machine": "64210.50", "fixed": "9600.00",
                         "total": "256210.50", "perOutputStone": "149.6165" },
               "ordersTotal": 9, "ordersCompleted": 9, "ordersDelayed": 2, "ordersWithDueDate": 9,
               "onTimeDeliveryRate": "0.777778" },
  "bottleneck": { "primaryStageId": "…", "primaryStageCode": "POLISHING", "reasonCode": "CONSTRAINED_POOL_WITH_QUEUE",
                  "config": { "wUtilization": "0.35", "wWait": "0.30", "wQueue": "0.20", "wBlocked": "0.15",
                              "scoreThreshold": "0.50", "utilizationThreshold": "0.85" } },
  "orders": [ { "orderId": "…", "orderNumber": "SDW-2026-0004", "completionAt": "…", "dueAt": "…",
                "outcome": "LATE", "delayed": true } ],
  "unfinished": [] }
```

(The numbers above illustrate the shape only.) `GET …/stages` returns the `simulation_stage_result` columns
as a list in sequence order; `GET …/resources` likewise for pools; `GET …/events?stageId=&orderId=&page=&size=`
returns the trace in the list envelope. `/resources` and `/events` are additions to architecture.md's
endpoint list, needed by the results screen (utilization chart and production timeline).

## B.6 Error cases

| Case | Status |
|---|---|
| Unknown scenario or run | 404 |
| Invalid snapshot at submission | 422 with `errors[]` |
| No permit | 429 with `Retry-After` |
| Results of a run that is not `COMPLETED` | 409 with `status` in the body |
| `Idempotency-Key` longer than 100 characters | 400 |

## B.7 Testing

- `SnapshotInputAdapterTest`: field-by-field mapping, epoch conversion, sorted output.
- `SimulationSubmissionServiceTest` (Mockito): idempotency key reuse, hash reuse, `force`, permit exhaustion, permit release on insert failure.
- `SimulationRunIT` (Testcontainers), using the seeded factory:
  - submit → poll → `COMPLETED`; results, stages, resources and events readable; stage rows count equals stage count.
  - same `Idempotency-Key` twice → one row. Two concurrent submissions with one key → one row.
  - same scenario twice without key → second returns `reused: true`; with `force=true` → new run with an equal summary (requirement 15.1).
  - editing a pool, a stage and the scenario's changes after submission leaves `input_snapshot`, hashes and results unchanged.
  - history tables unchanged by a run (spec 04 task 10).
  - failure injection (a `SimulationResultWriter` spy that throws after stage rows are saved) → run `FAILED`, zero result rows, safe message.
  - `StaleRunRecovery`: insert `PENDING` and `RUNNING` rows, start the runner, both `FAILED`.
  - claim is exclusive: two executors given one run id → engine invoked once.
  - results endpoint returns 409 while `PENDING`.
- `SimulationPerformanceTest` (tagged `slow`, not in the default `test` run): seeded baseline under two seconds.
