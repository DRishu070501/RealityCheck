# 07 Simulation Engine — Requirements

## Introduction

The simulation engine is the core of RealityCheck. It is a deterministic discrete-event simulation written in
plain Java (`com.realitycheck.simulation.engine`) that takes an immutable input and returns every number the
product displays about a simulated future. `.kiro/steering/simulation-rules.md` is the contract; where this
document and that one differ, simulation-rules.md wins and this document is wrong.

This spec has two parts:

- **Part A — the engine** (requirements 1-11): a pure function with no Spring, database, AI, clock, threads or
  randomness.
- **Part B — run execution** (requirements 12-16): submitting a run for a scenario, executing it in the
  background, persisting results atomically and exposing them through the API.

Snapshot assembly and scenario changes are spec 06. Comparison is spec 08.

## Requirements

### Requirement 1: Determinism and purity

**User Story:** As a factory owner, I want the same question to give the same answer every time, so that I
can trust and audit a result.

#### Acceptance Criteria

1. WHEN the engine is run twice with equal `SimulationInput` THE SYSTEM SHALL return results that are equal field by field, including the full event trace.
2. WHEN the engine runs THE SYSTEM SHALL NOT read the wall clock, use randomness, start threads, perform I/O, or use `double` or `float`.
3. WHEN the engine code is compiled THE SYSTEM SHALL have no import from Spring, Jakarta Persistence, Jackson or any other `com.realitycheck` package, enforced by an ArchUnit test.
4. WHEN a result is produced THE SYSTEM SHALL include `algorithmVersion` equal to `SimulationEngine.ALGORITHM_VERSION`.
5. WHEN the order of elements in any input collection is permuted THE SYSTEM SHALL return an equal result, because the engine sorts its inputs by stable keys before use.

### Requirement 2: Input validation

**User Story:** As a user, I want impossible configurations rejected before a run, so that I never see
results from a factory that cannot exist.

#### Acceptance Criteria

1. WHEN a pool has negative base capacity THE SYSTEM SHALL throw `InvalidSimulationInputException` with code `NEGATIVE_CAPACITY`.
2. WHEN a stage requirement references a pool that is not in the input THE SYSTEM SHALL fail with `UNKNOWN_POOL`.
3. WHEN a stage requirement's `unitsPerSlot` exceeds the pool's maximum capacity over the horizon THE SYSTEM SHALL fail with `UNSATISFIABLE_REQUIREMENT`.
4. WHEN a stage's rate is not greater than zero, or a yield is not in (0, 1] THE SYSTEM SHALL fail with `INVALID_RATE` or `INVALID_YIELD`.
5. WHEN the calendar has no working time and the input contains at least one unfinished batch THE SYSTEM SHALL fail with `EMPTY_CALENDAR`.
6. WHEN stage sequences are not exactly 1..N, or a batch refers to an unknown order or stage sequence THE SYSTEM SHALL fail with `INVALID_STRUCTURE`.
7. WHEN validation fails THE SYSTEM SHALL report every problem found, not only the first.

### Requirement 3: Event ordering

**User Story:** As an engineer, I want a total order over events, so that simultaneous events are always
processed the same way.

#### Acceptance Criteria

1. WHEN two events have different timestamps THE SYSTEM SHALL process the earlier first.
2. WHEN two events share a timestamp THE SYSTEM SHALL process them by type priority: `RESOURCE_CAPACITY_CHANGE` 0, `STAGE_COMPLETED` 1, `SHIFT_START` 2, `JOB_ARRIVAL` 3, `DISPATCH_ATTEMPT` 4.
3. WHEN two events share timestamp and type THE SYSTEM SHALL process them by ascending sequence number assigned at scheduling.
4. WHEN a completion and an arrival occur at the same instant THE SYSTEM SHALL make the units released by the completion available to the dispatch that follows at that instant.

### Requirement 4: Resource allocation

**User Story:** As a production manager, I want a job to start only when everything it needs is free, so that
the simulation reflects real constraints.

#### Acceptance Criteria

1. WHEN a job is considered for start THE SYSTEM SHALL start it only if every required pool has at least `unitsPerSlot` free units at that instant and the instant lies inside a working interval for that job.
2. WHEN a job starts THE SYSTEM SHALL take all of its units from all of its pools in one step; no state exists in which a job holds some of its requirements.
3. WHEN a job completes THE SYSTEM SHALL release all of its units at the completion event.
4. WHEN a pool's capacity is reduced below its allocated count THE SYSTEM SHALL let running jobs keep their units and start no new job on that pool until allocated is below capacity.
5. WHEN a stage has no requirements THE SYSTEM SHALL start every queued job at that stage at the next working instant.
6. WHEN a run ends with reason `COMPLETED` THE SYSTEM SHALL have zero allocated units in every pool.
7. WHEN several jobs are queued at a stage THE SYSTEM SHALL select by order priority (higher first), order due date (earlier first, none last), queue-entry time, then batch id.
8. WHEN several stages have startable jobs at one instant THE SYSTEM SHALL scan stages in descending sequence.

### Requirement 5: Processing time and calendar

**User Story:** As an operations manager, I want completion times that respect shifts, breaks and downtime,
so that the predicted dates are believable.

#### Acceptance Criteria

1. WHEN a job starts THE SYSTEM SHALL compute `workSeconds = setupSeconds + ceil(inputStones * 3600 / stonesPerHourPerSlot)`.
2. WHEN a job's work does not fit in the current working interval THE SYSTEM SHALL pause it at the interval end and resume at the next working interval, completing when `workSeconds` of working time have elapsed.
3. WHEN the factory is closed (outside shifts, in a break, or on a day without shifts) THE SYSTEM SHALL start no job and accrue no processing time.
4. WHEN a pool held by a running job has zero capacity for a period (full downtime) THE SYSTEM SHALL treat that period as non-working time for that job.
5. WHEN shifts are expanded THE SYSTEM SHALL convert local times to UTC using the factory time zone, subtract breaks, merge overlaps, and use half-open intervals `[start, end)`.
6. WHEN two jobs run at once on two slots THE SYSTEM SHALL report the elapsed time of one and the slot-time of two; the metrics never conflate them.

### Requirement 6: Yield and quantities

**User Story:** As a planner, I want stones and carats tracked separately through every stage, so that
neither is silently lost or confused.

#### Acceptance Criteria

1. WHEN a job completes a stage THE SYSTEM SHALL compute `outputStones = inputStones * stoneYield` and `outputCarats = inputCarats * caratYield`, each at scale 4 with HALF_EVEN rounding.
2. WHEN quantities move between stages THE SYSTEM SHALL carry the fractional values unchanged; no integer truncation occurs inside the engine.
3. WHEN a stage result is produced THE SYSTEM SHALL satisfy `input = output + loss` exactly for stones and for carats.
4. WHEN whole-stone figures are needed for display THE SYSTEM SHALL round only the final value HALF_EVEN and keep the unrounded value in the result.

### Requirement 7: Work in progress at the fork

**User Story:** As a user branching from the past, I want the simulation to continue from where the factory
actually was, so that the alternative future starts from reality.

#### Acceptance Criteria

1. WHEN a batch is `IN_PROGRESS` at the fork THE SYSTEM SHALL start the run with that job holding its units and with remaining work equal to `workSeconds` minus working seconds elapsed between its recorded start and the fork, floored at zero.
2. WHEN a batch is `QUEUED` at the fork THE SYSTEM SHALL place it in that stage's queue with its recorded queue-entry time.
3. WHEN a batch is a future arrival THE SYSTEM SHALL enqueue it at stage 1 at its arrival time.
4. WHEN a batch has completed stages before the fork THE SYSTEM SHALL NOT process those stages again.
5. WHEN any batch is simulated THE SYSTEM SHALL complete its stages in ascending sequence, each exactly once.

### Requirement 8: Termination

**User Story:** As an operator of the service, I want every run to end, so that a bad scenario cannot hang a
worker.

#### Acceptance Criteria

1. WHEN no events remain THE SYSTEM SHALL end with `terminationReason = COMPLETED`.
2. WHEN the next event lies beyond `forkTime + maxSimulatedSeconds` THE SYSTEM SHALL end with `HORIZON_REACHED`.
3. WHEN the number of processed events reaches `maxEvents` THE SYSTEM SHALL end with `EVENT_LIMIT_REACHED`.
4. WHEN a run ends on a limit THE SYSTEM SHALL return a normal result with metrics up to that point and a list of unfinished jobs; it SHALL NOT throw.
5. WHEN queued jobs can never start because capacity stays at zero THE SYSTEM SHALL end with `HORIZON_REACHED`.

### Requirement 9: Metrics

**User Story:** As a manager, I want precisely defined metrics, so that I can compare scenarios on the same
basis.

#### Acceptance Criteria

1. WHEN a run completes THE SYSTEM SHALL compute every metric in simulation-rules.md section 11 with exactly that definition.
2. WHEN a pool has zero available unit-seconds THE SYSTEM SHALL report its utilization as `null` with reason `NO_AVAILABLE_TIME`.
3. WHEN utilization is reported THE SYSTEM SHALL return a value in [0, 1] or `null`.
4. WHEN the input contains no unfinished batches THE SYSTEM SHALL report zero processing seconds, zero waiting seconds, zero machine cost and `terminationReason = COMPLETED`.
5. WHEN cost is reported THE SYSTEM SHALL give the total and its components (employee, machine, fixed) and the cost per output stone (`null` when output is zero).
6. WHEN order results are reported THE SYSTEM SHALL give, per order, completion time (or `null`), due time, and `ON_TIME`, `LATE` or `UNFINISHED`.

### Requirement 10: Bottleneck detection

**User Story:** As a production manager, I want to know which stage constrains the factory and why, so that I
invest in the right place.

#### Acceptance Criteria

1. WHEN a run completes THE SYSTEM SHALL compute for every stage the four signals and the weighted score defined in simulation-rules.md section 12.
2. WHEN the highest-scoring stage has score ≥ 0.5 and its limiting pool's utilization ≥ 0.85 THE SYSTEM SHALL report it as the primary bottleneck; otherwise it SHALL report that there is no clear bottleneck.
3. WHEN bottleneck results are returned THE SYSTEM SHALL include every stage's raw signal values, normalised values, score, limiting pool and a machine-readable reason code.
4. WHEN a stage has the longest processing time but no queue and spare capacity THE SYSTEM SHALL NOT report it as the bottleneck.
5. WHEN weights or thresholds are supplied in the input THE SYSTEM SHALL use them instead of the defaults.

### Requirement 11: Correctness properties

**User Story:** As an engineer, I want the engine's invariants checked over generated inputs, so that edge
cases are found by the tests and not by users.

#### Acceptance Criteria

1. WHEN the property suite runs THE SYSTEM SHALL verify properties 1-10 of simulation-rules.md section 13 with jqwik.
2. WHEN property 10 (adding units never increases completion time) is tested THE SYSTEM SHALL restrict generation to the single-stage case with identical job order, as the rule states.
3. WHEN multi-stage monotonicity is examined THE SYSTEM SHALL report the share of generated cases in which added capacity lengthened the schedule as a statistic and SHALL NOT assert on it.

### Requirement 12: Submitting a run

**User Story:** As a user, I want to start a simulation and get an id back at once, so that the UI can show
progress.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/scenarios/{id}/simulate` THE SYSTEM SHALL assemble and validate the snapshot (spec 06), insert a `simulation_run` in status `PENDING` containing the snapshot, both hashes and the algorithm version, and return 202 with the run id.
2. WHEN the snapshot is invalid THE SYSTEM SHALL return 422 with the validation errors and create no run.
3. WHEN the request carries an `Idempotency-Key` already used for this scenario THE SYSTEM SHALL return the original run and create nothing.
4. WHEN a `COMPLETED` run with the same `input_hash` exists for the scenario and `force` is not true THE SYSTEM SHALL return that run with `reused: true` and execute nothing.
5. WHEN the executor has no free permit THE SYSTEM SHALL return 429 and create no run.
6. WHEN a run row has been written THE SYSTEM SHALL never update its `input_snapshot`, `input_hash`, `base_state_hash` or `algorithm_version`.

### Requirement 13: Executing a run

**User Story:** As a user, I want a run to either finish completely or fail cleanly, so that I never see half
a result.

#### Acceptance Criteria

1. WHEN a worker picks up a run THE SYSTEM SHALL move it from `PENDING` to `RUNNING` with a conditional update, and SHALL stop if the update affects no row.
2. WHEN the engine returns THE SYSTEM SHALL write stage results, resource results, event results and the summary and set `COMPLETED` in one transaction.
3. WHEN anything fails during execution or persistence THE SYSTEM SHALL roll back all result rows and set the run to `FAILED` with a message that contains no stack trace or class name.
4. WHEN the application starts THE SYSTEM SHALL mark every run left in `PENDING` or `RUNNING` as `FAILED` with message "Interrupted by restart".
5. WHEN a run executes THE SYSTEM SHALL read only its own `input_snapshot`; it SHALL NOT read live configuration or history tables.
6. WHEN a run executes THE SYSTEM SHALL NOT write to any table outside `simulation_*`.

### Requirement 14: Reading results

**User Story:** As a user, I want to poll a run and then read its results, so that the UI can show progress
and outcomes.

#### Acceptance Criteria

1. WHEN a client sends `GET /api/simulations/{runId}` THE SYSTEM SHALL return status, timestamps, scenario id, algorithm version, hashes, termination reason and failure message.
2. WHEN a client sends `GET /api/simulations/{runId}/results` for a `COMPLETED` run THE SYSTEM SHALL return the summary metrics, order results, resource results and bottleneck result with `source: "SIMULATED"`.
3. WHEN results are requested for a run that is not `COMPLETED` THE SYSTEM SHALL return 409 with the current status.
4. WHEN a client sends `GET /api/simulations/{runId}/stages` THE SYSTEM SHALL return per-stage results including bottleneck signals, in sequence order.
5. WHEN a client sends `GET /api/simulations/{runId}/events` THE SYSTEM SHALL return the simulated event trace paginated in `seq` order, filterable by stage and order.
6. WHEN a client sends `GET /api/scenarios/{id}/simulations` THE SYSTEM SHALL return the scenario's runs, newest first.

### Requirement 15: Reproducibility of stored runs

**User Story:** As an auditor, I want to re-execute a stored run and get the same numbers, so that results
can be verified later.

#### Acceptance Criteria

1. WHEN the engine is re-run on a stored `input_snapshot` with the same algorithm version THE SYSTEM SHALL produce a summary equal to the stored summary.
2. WHEN the algorithm version in code differs from a stored run's version THE SYSTEM SHALL still serve the stored results unchanged and label them with their version.

### Requirement 16: Performance bounds

**User Story:** As a presenter, I want demo simulations to finish quickly, so that the live flow is smooth.

#### Acceptance Criteria

1. WHEN the demonstration factory's baseline scenario is simulated on a developer laptop THE SYSTEM SHALL complete the engine run in under two seconds.
2. WHEN event results are persisted THE SYSTEM SHALL use JDBC batching.
