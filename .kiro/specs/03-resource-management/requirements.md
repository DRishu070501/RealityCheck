# 03 Resource Management — Requirements

## Introduction

Resources are **pools** of interchangeable units, of kind `MACHINE` or `EMPLOYEE` (architecture.md,
"Resources are pools"). A pool has a capacity in whole units, an hourly cost per unit, and a status.
Time-bounded changes to a pool's capacity (planned downtime, absence, temporary extra units) are stored as
availability windows. Per-person rosters are a non-goal: "five employees unavailable" is a capacity delta of
-5 on an employee pool.

Pools follow the factory calendar (spec 01). There is no separate per-pool weekly schedule in the MVP;
pool-specific availability is expressed with windows.

## Requirements

### Requirement 1: Resource pools

**User Story:** As a production manager, I want to register machine groups and employee skill groups with
their capacity and cost, so that the twin knows what the factory can do at once.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/resources` with factory id, kind, category, name, capacity and hourly cost THE SYSTEM SHALL create the pool and return 201.
2. WHEN `capacity` is negative or not an integer, or `hourlyCost` is negative THE SYSTEM SHALL return 400.
3. WHEN a pool with the same name exists in the factory THE SYSTEM SHALL return 409.
4. WHEN a client sends `GET /api/resources?factoryId=&kind=` THE SYSTEM SHALL return the pools in the list envelope ordered by kind then name, optionally filtered by kind.
5. WHEN a pool is returned THE SYSTEM SHALL include `hourlyCost` as cost per unit per hour in the factory currency, and `capacity` as a unit count.

### Requirement 2: Updating a pool

**User Story:** As an operations manager, I want to change a pool's capacity, cost or status, so that the
twin tracks the real factory.

#### Acceptance Criteria

1. WHEN a client sends `PUT /api/resources/{id}` with the current `version` THE SYSTEM SHALL update name, category, capacity, hourly cost and status and return the new version.
2. WHEN the supplied `version` is stale THE SYSTEM SHALL return 409 and change nothing.
3. WHEN the update would set status `INACTIVE` on a pool referenced by a `stage_requirement` of an ACTIVE workflow THE SYSTEM SHALL return 409 naming the stages.
4. WHEN a pool is updated THE SYSTEM SHALL NOT alter any existing simulation run, because each run holds its own input snapshot (spec 07).
5. WHEN a client attempts to change `kind` or `factoryId` THE SYSTEM SHALL return 400.

### Requirement 3: Availability windows

**User Story:** As a supervisor, I want to record periods when part or all of a pool is unavailable, so that
simulations do not schedule work on machines under maintenance or staff on leave.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/resources/{id}/availability` with `startsAt`, `endsAt`, `capacityDelta` and `reason` THE SYSTEM SHALL store the window and return 201.
2. WHEN `endsAt` is not after `startsAt`, or `capacityDelta` is zero THE SYSTEM SHALL return 400.
3. WHEN a client sends `GET /api/resources/{id}/availability?from=&to=` THE SYSTEM SHALL return the windows overlapping the range ordered by `startsAt`, and the effective capacity timeline for the range (base capacity plus the sum of active deltas, floored at zero).
4. WHEN overlapping windows would make effective capacity negative THE SYSTEM SHALL accept them and report effective capacity as zero for that period.
5. WHEN a window is recorded THE SYSTEM SHALL also append a `RESOURCE_UNAVAILABLE` operational event (spec 04) for windows with a negative delta, in the same transaction.

### Requirement 4: Current utilization display

**User Story:** As a manager, I want to see how loaded each pool is, so that I can spot idle or overloaded
resources.

#### Acceptance Criteria

1. WHEN a pool is listed THE SYSTEM SHALL include `unitsInUse` at the present moment, derived from recorded events (units held by batches with a `STAGE_STARTED` and no `STAGE_COMPLETED`, multiplied by the stage's `unitsPerSlot`), with `source: "ACTUAL"`.
2. WHEN utilization over a period is needed THE SYSTEM SHALL take it from a simulation run's resource results (spec 07) labelled `SIMULATED`; this spec does not compute historical utilization.

### Requirement 5: Demonstration resources

**User Story:** As a judge, I want machines and employees preconfigured, so that the factory is explorable
at once.

#### Acceptance Criteria

1. WHEN the demo seed runs THE SYSTEM SHALL create synthetic machine pools and employee pools sufficient to satisfy every stage requirement of the demonstration workflow, including a "Polishing wheels" machine pool, a "Polishers" employee pool and a "Cutting machines" pool.
2. WHEN the demo seed runs THE SYSTEM SHALL create at least one historical downtime window so the availability view is not empty.
