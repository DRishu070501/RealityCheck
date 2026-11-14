# 07 Simulation Engine — Tasks

Milestones: **M4 — Simulation engine** for Part A (tasks 1-14). **M5 — Scenario branching** for Part B
(tasks 15-22), which needs spec 06's snapshot.

Part A needs nothing but the JDK, JUnit and jqwik. Every task's tests name the simulation-rules.md rule they
check. Any later change to engine output bumps `ALGORITHM_VERSION`.

## Part A — Engine (M4)

- [x] 1. Create the engine package, input records and the architecture test
  - `SimulationInput` and nested records (design A.2), `SimulationEngine` with `ALGORITHM_VERSION = "1.0.0"` and a `run` that throws `UnsupportedOperationException`.
  - `EngineArchitectureTest`: ArchUnit import rule and the forbidden-token source scan.
  - `InputBuilder` test fixture.
  - _Requirements: 1.2, 1.3, 1.4_

- [x] 2. Implement `IntervalSet`
  - Union, subtract, intersect on half-open `long` intervals; always sorted and merged.
  - Test: examples plus jqwik properties (result is sorted and disjoint; `a ∪ b` covers both; `(a \ b) ∩ b` is empty; total length identities).
  - _Requirements: 5.5_

- [x] 3. Implement `WorkingCalendar`
  - Day expansion with time zone, breaks, merge, 24:00 end; `isWorking`, `nextWorkingStart`, `workingSecondsBetween`, `intervalsBetween`; per-day cache in a `TreeMap`.
  - Test: `WorkingCalendarTest` (demo calendar week, `Asia/Kolkata` conversion, a daylight-saving zone, empty calendar, overlapping shifts).
  - _Requirements: 5.3, 5.5_

- [x] 4. Implement `CapacityProfile` and completion-time walking
  - Segments from base capacity and windows, floor at zero, `zeroIntervals`, `boundariesAfter`, `maxCapacity`; job working set `W(job)`; `completionTime(start, workSeconds, W)`.
  - Test: within one interval, across a break, a night and a closed day; zero work; never completes → `Long.MAX_VALUE`; full downtime pauses, partial reduction does not.
  - _Requirements: 5.1, 5.2, 5.4_

- [x] 5. Implement `InputValidator`
  - All codes in requirement 2; collects every problem.
  - Test: `InputValidatorTest`, one test per code and one with three simultaneous problems.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

- [x] 6. Implement the clock, events and event queue
  - `SimulationClock`, `SimulationEvent`, `EventType` with priorities, `SimulationEventQueue` with sequence numbers.
  - Test: ordering by time, then priority, then sequence; jqwik: polling any scheduled set yields a sequence sorted by the three keys; clock rejects going backwards.
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 7. Implement `ResourcePool`, `ResourceAllocation` and `SimulationState`
  - Pool arithmetic with over-commit allowed on capacity reduction, release guard; state containers (queues as `TreeSet` with the dispatch comparator, running jobs in a `TreeMap`).
  - Test: allocate and release; free never negative; release beyond allocated throws; comparator order.
  - _Requirements: 4.3, 4.4, 4.7_

- [x] 8. Implement `StageScheduler` dispatch
  - Descending stage scan, atomic check-then-take, work-seconds formula with `CEILING`, completion scheduling, trace.
  - Test: `StageSchedulerTest` for atomic allocation (machine free, employees short), unlimited-slot stages, dispatch order, downstream-first with a shared pool, no start outside working time.
  - _Requirements: 4.1, 4.2, 4.5, 4.7, 4.8, 5.1, 5.3_

- [x] 9. Implement the event loop
  - Initialisation order, the five event handlers, `requestDispatch`, `scheduleNextShiftStart`, limits and termination reasons, yield at completion, order completion, unfinished-job list.
  - Test: `SimulationEngineTest` single job; two jobs two slots; same-instant completion and arrival; eight-stage yield chain with exact decimals; zero-capacity → `HORIZON_REACHED`; small `maxEvents` → `EVENT_LIMIT_REACHED`; no batches → `COMPLETED` with zero events of work.
  - _Requirements: 3.4, 4.3, 4.6, 6.1, 6.2, 7.3, 7.5, 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 10. Implement work in progress at the fork
  - `IN_PROGRESS` initialisation with remaining work and forced allocation; `QUEUED` with recorded entry time; no reprocessing of earlier stages.
  - Test: remaining work computed across a night; zero remaining completes at `T0`; in-progress job above reduced capacity sets `overcommitted`; queued-before-fork order respected.
  - _Requirements: 7.1, 7.2, 7.4, 7.5_

- [x] 11. Implement `SimulationMetricsCalculator`
  - Accruals in `state.accrue`, per-job figures, pool busy/available/utilization with null reason and cap, cost components, throughput, yield, order outcomes, rounding of display stones.
  - Test: `SimulationMetricsCalculatorTest` with a hand-worked three-stage example asserting every field; zero available time → `null`; no due dates → `onTimeDeliveryRate` null; conservation per stage.
  - _Requirements: 5.6, 6.3, 6.4, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_

- [x] 12. Implement `BottleneckAnalyzer`
  - Signals, normalisation, score, decision table, reason codes, configurable weights.
  - Test: `BottleneckAnalyzerTest` for each row of the decision table, the long-stage-with-spare-capacity case, custom weights changing the outcome, ties.
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [x] 13. Write the property suite
  - Generators for valid inputs; `SimulationEngineProperties` with properties 1-10 as listed in design A.9; `MultiStageMonotonicityReport` as a statistic.
  - _Requirements: 1.1, 1.5, 11.1, 11.2, 11.3_

- [x] 14. Document the engine
  - `docs/simulation.md`: units, the event loop in prose, metric definitions, bottleneck scoring, known limits (no splitting, no preemption, scheduling anomalies). Link from README.
  - _Requirements: 9.1, 10.3_

## Part B — Run execution (M5)

- [ ] 15. Add the simulation schema
  - Flyway migration for `simulation_run`, `simulation_stage_result`, `simulation_resource_result`, `simulation_event_result` with indexes; entities and repositories; enable `hibernate.jdbc.batch_size` and ordered inserts.
  - Test: `SimulationRepositoryIT` for the unique `(scenario_id, idempotency_key)` (nulls allowed many times) and cascade delete.
  - _Requirements: 12.1, 16.2_

- [ ] 16. Implement `SnapshotInputAdapter`
  - Mapping from spec 06 snapshot records to `SimulationInput`.
  - Test: `SnapshotInputAdapterTest`.
  - _Requirements: 13.5_

- [ ] 17. Implement `SimulationResultWriter`
  - Entities and summary JSON from `SimulationResult`, names copied from the snapshot, one transaction, batched event inserts.
  - Test: integration test writing a known result and reading every table back.
  - _Requirements: 13.2, 16.2_

- [ ] 18. Implement submission
  - `SimulationExecutorConfig` (executor, semaphore, properties); `SimulationSubmissionService` per design B.3; `POST /api/scenarios/{id}/simulate`.
  - Test: `SimulationSubmissionServiceTest`; `SimulationRunIT` idempotency and reuse cases, 422 on invalid snapshot, 429 with permits set to zero.
  - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_

- [ ] 19. Implement execution and failure handling
  - `SimulationRunExecutor` per design B.4 with the conditional claim and safe failure message.
  - Test: `SimulationRunIT` happy path, failure injection leaves zero result rows, exclusive claim, history and live configuration untouched.
  - _Requirements: 13.1, 13.2, 13.3, 13.5, 13.6_

- [ ] 20. Implement stale-run recovery
  - `StaleRunRecovery` runner.
  - Test: `SimulationRunIT` recovery case.
  - _Requirements: 13.4_

- [ ] 21. Implement the read endpoints
  - `GET /api/simulations/{runId}`, `/results`, `/stages`, `/resources`, `/events`, and `GET /api/scenarios/{id}/simulations`; `SimulationResultsReader` for other modules; scenario list gains `latestRun` (spec 06 task 3).
  - Test: `SimulationApiIT` for shapes, 409 before completion, pagination and filters on events, ordering.
  - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6_

- [ ] 22. Verify reproducibility and performance
  - Test: re-run the engine on a stored snapshot and compare with the stored summary; stored results served unchanged when the code's version constant differs (set by a test property); `SimulationPerformanceTest`.
  - Update `docs/api.md` (Simulation section) and add `/resources`, `/events` to the endpoint list in architecture.md.
  - _Requirements: 15.1, 15.2, 16.1_

## Implementation notes (Milestone 4)

Part A (tasks 1-14) is built. Part B (tasks 15-22: stored, asynchronous runs) is Milestone 5, because a run
belongs to a scenario. Differences from design.md:

- **Bottleneck gate.** The primary bottleneck needs score >= 0.5 and **saturation** >= 0.5, where saturation is
  blocked working time over the stage's active working time (queued or running). The fourth signal is
  saturation rather than blocked share of the whole run; blocked share is still reported. The reason code
  `QUEUE_WITHOUT_SATURATED_POOL` became `NO_SUSTAINED_QUEUE`. `BottleneckConfig` fields are renamed
  accordingly (`saturationWeight`, `saturationThreshold`). See simulation-rules.md section 12 for why.
- **Orders.** `OrderSpec` has no `completedBatchCount`: the assembler includes only orders with unfinished
  batches, and an order completes when all of its batches in the input have finished.
- **Unfinished jobs** may also be `NOT_ARRIVED` (a future arrival beyond the horizon).
- **Termination.** If every batch has finished, the result is `COMPLETED` even when a leftover wake-up event
  lies beyond the horizon.
- **Shift starts** are scheduled beyond the horizon when no interval starts within it, so stuck work ends as
  `HORIZON_REACHED` without spinning.
- `SimulationContext` precomputes per-stage working time (calendar minus zero-capacity periods of the stage's
  pools), so a job's working set is a lookup rather than recomputed per job.
- The "forbidden tokens" check is `EnginePurityTest` (source scan), alongside the ArchUnit rules that already
  covered the package.
- Added beyond the spec: `SimulationInputAssembler` (the Part B `SnapshotInputAdapter`'s mapping, built early
  against live data rather than a stored snapshot), `ProductionReader`, and a read-only
  `GET /api/factories/{id}/baseline-simulation` so the engine can be exercised end to end before scenarios
  exist. It stores nothing.
- Tests: `SimulationEngineTest` (42 hand-computed examples), `InputValidatorTest`, `WorkingCalendarTest`
  (including daylight-saving gaps and overlaps and interval-set properties), `SimulationEngineProperties`
  (rules 13.1-13.10 and the multi-stage monotonicity report), `DemoFactoryScenarioTest` (the product's
  scenarios on a demo-shaped factory), `EnginePurityTest`, and `BaselineSimulationIT` against the seeded factory.
