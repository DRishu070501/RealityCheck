# 06 Scenario Branching — Requirements

## Introduction

A scenario is a named "what if": a fork timestamp plus an ordered list of changes to apply to the factory as
it stood at that instant. Scenarios never touch live tables or recorded history. This spec covers scenario
and change management, the six change types, branching from a parent scenario, and the assembly of the
immutable **input snapshot** that the simulation consumes. Running the simulation and storing results is
spec 07; comparing runs is spec 08.

## Requirements

### Requirement 1: Scenario management

**User Story:** As an operations manager, I want to create and name scenarios from a historical instant, so
that I can explore alternatives without affecting real data.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/scenarios` with factory id, name and `forkAt` THE SYSTEM SHALL create the scenario and return 201.
2. WHEN `forkAt` is earlier than the factory's first event or later than the current time THE SYSTEM SHALL return 400.
3. WHEN the name is blank, longer than 120 characters, or already used by another scenario of the factory THE SYSTEM SHALL return 400 or 409 respectively.
4. WHEN a client sends `PATCH /api/scenarios/{id}` with `name` or `description` and the current `version` THE SYSTEM SHALL update them; a stale version returns 409.
5. WHEN a client attempts to change `forkAt` on a scenario that has any simulation run THE SYSTEM SHALL return 409; a new scenario must be created instead.
6. WHEN a client sends `GET /api/scenarios?factoryId=` THE SYSTEM SHALL return scenarios in the list envelope with change count, latest run status and latest run id.
7. WHEN a client sends `GET /api/scenarios/{id}` THE SYSTEM SHALL return the scenario with its changes in ordinal order.

### Requirement 2: Branching

**User Story:** As a planner, I want to branch a scenario from another, so that I can build on an idea
without redoing it.

#### Acceptance Criteria

1. WHEN a client creates a scenario with `parentScenarioId` THE SYSTEM SHALL copy the parent's `forkAt` (unless one is supplied) and the parent's changes, preserving order, into the new scenario.
2. WHEN the parent is later edited THE SYSTEM SHALL NOT alter the child; the copy is taken once at creation.
3. WHEN `parentScenarioId` belongs to another factory or does not exist THE SYSTEM SHALL return 400.
4. WHEN a scenario is returned THE SYSTEM SHALL include `parentScenarioId` and, for each change, `inherited: true` if it was copied from the parent.

### Requirement 3: Scenario changes

**User Story:** As a user, I want to add, edit and remove changes on a scenario, so that I can describe the
alternative I want to test.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/scenarios/{id}/changes` with a `changeType` and `payload` THE SYSTEM SHALL validate the payload against the typed record for that type, append the change with the next ordinal, and return 201.
2. WHEN the payload fails validation THE SYSTEM SHALL return 400 naming the payload field.
3. WHEN a payload references a resource or stage that does not exist in the scenario's factory THE SYSTEM SHALL return 400.
4. WHEN a client sends `PUT /api/scenarios/{id}/changes/{changeId}` THE SYSTEM SHALL replace the payload after the same validation; `changeType` cannot change.
5. WHEN a client sends `DELETE /api/scenarios/{id}/changes/{changeId}` THE SYSTEM SHALL remove the change and renumber ordinals without gaps.
6. WHEN changes are added, edited or removed THE SYSTEM SHALL leave every existing simulation run and its snapshot unchanged.
7. WHEN a scenario has no changes THE SYSTEM SHALL treat it as a baseline for its fork timestamp.

### Requirement 4: Change types

**User Story:** As a manager, I want to express the decisions I am actually considering, so that the
simulation answers real questions.

#### Acceptance Criteria

1. WHEN a `RESOURCE_CAPACITY` change is applied THE SYSTEM SHALL set the pool's base capacity by `ABSOLUTE` value, by `DELTA` units, or by `PERCENT` (result rounded down to whole units), never below zero.
2. WHEN a `RESOURCE_UNAVAILABLE` change is applied THE SYSTEM SHALL reduce the pool's capacity by `units` for the given window, or from the fork to the end of the horizon when no window is given.
3. WHEN a `RESOURCE_DOWNTIME` change is applied THE SYSTEM SHALL reduce the pool's capacity to zero for the window.
4. WHEN a `DEMAND_VOLUME` change with `percent` p is applied THE SYSTEM SHALL multiply the stones and carats of every batch that has not started its first stage at the fork by `(1 + p/100)`, at scale 4 HALF_EVEN, without rounding stones to integers.
5. WHEN a `PROCESSING_RATE` change is applied THE SYSTEM SHALL set the stage's `stonesPerHour` by `ABSOLUTE` value or by `PERCENT`, and reject a result that is not greater than zero.
6. WHEN a `WORKING_SCHEDULE` change is applied THE SYSTEM SHALL either `ADD` the given shifts to the factory calendar or `REPLACE` the calendar with them, and reject overlapping shifts.
7. WHEN several changes are present THE SYSTEM SHALL apply them in ordinal order, so a later change sees the effect of earlier ones.
8. WHEN a change would produce an impossible configuration (negative capacity before flooring from an `ABSOLUTE` value, percent below -100, empty calendar after `REPLACE`, window ending before it starts or before the fork) THE SYSTEM SHALL reject it at creation with 400.

### Requirement 5: Scenario validation before a run

**User Story:** As a user, I want to know before I run whether the scenario can be simulated, so that I do
not wait for a run that cannot work.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/scenarios/{id}/validate` THE SYSTEM SHALL assemble the snapshot without persisting anything and return `valid`, a list of errors, and a list of warnings.
2. WHEN the changed configuration leaves a stage whose requirement exceeds the pool's maximum capacity over the horizon THE SYSTEM SHALL report an error naming the stage and pool.
3. WHEN a change has no effect (for example a `PERCENT` capacity change that rounds to the same capacity, or `DEMAND_VOLUME` when no batch is unstarted) THE SYSTEM SHALL report a warning.
4. WHEN the response is returned THE SYSTEM SHALL include a preview of resulting pool capacities, stage rates and weekly working seconds, each with its before and after value.

### Requirement 6: Input snapshot

**User Story:** As an engineer, I want each run to carry a complete, immutable description of its input, so
that results are reproducible and unaffected by later edits.

#### Acceptance Criteria

1. WHEN a snapshot is assembled THE SYSTEM SHALL include the fork timestamp, factory zone and currency, calendar, stages with requirements, pools with capacity windows, orders, batch states reconstructed at the fork (spec 05), simulation limits, bottleneck configuration, the list of applied changes, and a `schemaVersion`.
2. WHEN a snapshot is assembled THE SYSTEM SHALL compute `baseStateHash` over the snapshot before changes are applied and `inputHash` over the snapshot after changes together with the algorithm version.
3. WHEN two scenarios of one factory share a `forkAt` and no history or configuration changed in between THE SYSTEM SHALL produce equal `baseStateHash` values.
4. WHEN a snapshot is assembled THE SYSTEM SHALL sort every collection by a stable key so the same data always serialises to the same bytes.
5. WHEN orders were placed after the fork timestamp in recorded history THE SYSTEM SHALL include them as future arrivals at their recorded `placedAt`, so a baseline run can be compared with what actually happened; `includeFutureOrders=false` on the scenario excludes them.

### Requirement 7: Preconfigured scenarios

**User Story:** As a judge, I want ready-made scenarios, so that I can run comparisons immediately.

#### Acceptance Criteria

1. WHEN the demo seed runs THE SYSTEM SHALL create six scenarios sharing one fork timestamp: "Current factory operations" (no changes), "Add two polishing machines", "Increase production demand by 20%", "Make five employees unavailable", "Reduce cutting capacity by 30%", "Introduce an additional shift".
2. WHEN the demo seed runs THE SYSTEM SHALL create them through the scenario service so every change passes validation, and SHALL NOT pre-populate simulation results.
