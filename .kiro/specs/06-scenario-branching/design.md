# 06 Scenario Branching — Design

## Components

Package `com.realitycheck.scenario`:

| Class | Responsibility |
|---|---|
| `api.ScenarioController` | `GET/POST /api/scenarios`, `GET/PATCH /api/scenarios/{id}`, `POST /api/scenarios/{id}/changes`, `PUT/DELETE /api/scenarios/{id}/changes/{changeId}`, `POST /api/scenarios/{id}/validate`. |
| `api.*` | `CreateScenarioRequest`, `UpdateScenarioRequest`, `ScenarioResponse`, `ScenarioSummaryResponse`, `ScenarioChangeRequest`, `ScenarioChangeResponse`, `ScenarioValidationResponse`. |
| `service.ScenarioService` | CRUD, branching (copy-on-create), ordinal maintenance. |
| `service.ChangePayloads` | Sealed interface `ChangePayload` with one record per change type; JSON ↔ record conversion; field validation. |
| `service.SnapshotAssembler` | Builds the base snapshot from live configuration and reconstructed state. |
| `service.ChangeApplier` | Pure function: `(Snapshot, List<ChangePayload>) → Snapshot`. Never touches the database. |
| `service.SnapshotValidator` | Structural errors and no-effect warnings; mirrors the engine's input validation (spec 07) so problems surface before submission. |
| `service.ScenarioSnapshotService` | `assemble(scenarioId) → AssembledSnapshot(snapshot, baseStateHash, inputHash)`. The single entry point used by spec 07's submission. |
| `domain.Scenario`, `ScenarioChange`, `ChangeType` | Entities and enum. |
| `snapshot.*` | The snapshot records (below). Plain records, Jackson-serialisable, no entity references. |
| `demo.ScenarioSeedSection` (order 50) | The six preconfigured scenarios. |

The comparison classes that also live in `scenario` are specified in spec 08.

## Data model

```sql
scenario (
  id                    uuid primary key,
  factory_id            uuid not null references factory(id),
  name                  varchar(120) not null,
  description           text,
  parent_scenario_id    uuid references scenario(id) on delete set null,
  fork_at               timestamptz not null,
  include_future_orders boolean not null default true,
  version               bigint not null default 0,
  created_at            timestamptz not null,
  updated_at            timestamptz not null,
  unique (factory_id, name)
)

scenario_change (
  id          uuid primary key,
  scenario_id uuid not null references scenario(id) on delete cascade,
  ordinal     integer not null check (ordinal >= 1),
  change_type varchar(32) not null check (change_type in
              ('RESOURCE_CAPACITY','RESOURCE_UNAVAILABLE','RESOURCE_DOWNTIME',
               'DEMAND_VOLUME','PROCESSING_RATE','WORKING_SCHEDULE')),
  payload     jsonb not null,
  inherited   boolean not null default false,
  created_at  timestamptz not null,
  updated_at  timestamptz not null,
  unique (scenario_id, ordinal) deferrable initially deferred
)
```

Indexes: `scenario(factory_id)`, `scenario(parent_scenario_id)`. The deferred unique constraint lets
renumbering happen inside one transaction. Editing a scenario's changes bumps `scenario.version`
(the scenario is the aggregate root for optimistic locking).

## Change payloads

| Type | Payload | Validation |
|---|---|---|
| `RESOURCE_CAPACITY` | `{ "resourceId", "mode": "ABSOLUTE"\|"DELTA"\|"PERCENT", "value" }` | `ABSOLUTE` ≥ 0 integer; `DELTA` non-zero integer; `PERCENT` ≥ -100, non-zero decimal |
| `RESOURCE_UNAVAILABLE` | `{ "resourceId", "units", "from"?, "to"? }` | `units` ≥ 1; if both given `to > from`; `to > forkAt` |
| `RESOURCE_DOWNTIME` | `{ "resourceId", "from", "to" }` | `to > from`; `to > forkAt` |
| `DEMAND_VOLUME` | `{ "percent" }` | > -100, non-zero, ≤ 500 |
| `PROCESSING_RATE` | `{ "stageId", "mode": "ABSOLUTE"\|"PERCENT", "value" }` | `ABSOLUTE` > 0; `PERCENT` > -100, non-zero |
| `WORKING_SCHEDULE` | `{ "mode": "ADD"\|"REPLACE", "shifts": [ { "dayOfWeek", "startTime", "endTime", "breaks": [] } ] }` | same shift rules as spec 01; `REPLACE` needs ≥ 1 shift |

Application rules (`ChangeApplier`):

- `RESOURCE_CAPACITY`: `ABSOLUTE` → value; `DELTA` → `max(0, capacity + value)`; `PERCENT` →
  `max(0, floor(capacity * (100 + value) / 100))`. Rounding down means capacity is never overstated;
  the validation preview shows the resulting unit count so "-30%" on 4 machines visibly becomes 2.
- `RESOURCE_UNAVAILABLE`: adds a capacity window `(from ?? forkAt, to ?? horizonEnd, -units)`.
- `RESOURCE_DOWNTIME`: adds a window `(from, to, -capacityAtThatPointInTheChangeList)`; the engine floors at zero.
- `DEMAND_VOLUME`: for batches in state `QUEUED` at stage 1 or `FUTURE_ARRIVAL`:
  `stones = stones * (100 + p) / 100`, `carats` likewise, scale 4 HALF_EVEN.
- `PROCESSING_RATE`: `ABSOLUTE` → value; `PERCENT` → `rate * (100 + value) / 100` at scale 4 HALF_EVEN.
- `WORKING_SCHEDULE`: `ADD` → union with existing shifts, then the overlap check; `REPLACE` → the given list.
  Shifts apply to the whole factory, so all pools are available in an added shift at their normal capacity.
  A scenario that wants a skeleton crew on the extra shift combines this with `RESOURCE_UNAVAILABLE` windows.

The demo scenarios map to: `RESOURCE_CAPACITY {Polishing wheels, DELTA, +2}`; `DEMAND_VOLUME {20}`;
`RESOURCE_UNAVAILABLE {Polishers, 5}`; `RESOURCE_CAPACITY {Cutting machines, PERCENT, -30}`;
`WORKING_SCHEDULE {ADD, Mon-Sat 18:00-22:00}`. "Add two polishing machines" adds wheels only; whether
polishers then become the constraint is exactly what the simulation shows.

## Snapshot

```
Snapshot(
  int schemaVersion,                      // 1
  UUID factoryId, String timeZone, String currencyCode,
  Instant forkAt,
  CalendarSnapshot calendar,              // shifts[{dayOfWeek, startTime, endTime, breaks[]}], sorted
  List<StageSnapshot> stages,             // sorted by sequence
  List<PoolSnapshot> pools,               // sorted by id
  List<OrderSnapshot> orders,             // sorted by id
  List<BatchSnapshot> batches,            // sorted by id
  LimitsSnapshot limits,                  // maxSimulatedSeconds, maxEvents
  BottleneckConfigSnapshot bottleneck,    // weights and thresholds
  List<AppliedChange> appliedChanges      // ordinal, type, payload — empty in the base snapshot
)
StageSnapshot(id, code, name, sequence, stonesPerHour, setupSeconds, stoneYield, caratYield,
              fixedCostPerJob, List<RequirementSnapshot(poolId, unitsPerSlot)> requirements /* by poolId */)
PoolSnapshot(id, kind, category, name, baseCapacity, hourlyCost,
             List<CapacityWindowSnapshot(from, to, delta)> windows /* by from, to, delta */)
OrderSnapshot(id, orderNumber, priority, dueAt, placedAt)
BatchSnapshot(id, orderId, batchNumber, state /* QUEUED | IN_PROGRESS | FUTURE_ARRIVAL */,
              stageSequence, stones, carats, queuedAt, startedAt, arrivalAt)
```

Completed batches are not included as work; orders that are fully completed at the fork are omitted.
Orders partially completed keep only their unfinished batches, plus `completedBatchCount` on the order so the
engine can decide order completion correctly.

Assembly (`ScenarioSnapshotService.assemble`):

```
state   = FactoryStateReconstructor.reconstruct(factoryId, forkAt)           (spec 05)
base    = SnapshotAssembler.build(factory, calendar, workflow, pools + availability windows overlapping
          [forkAt, forkAt + maxSimulatedSeconds], state, futureOrders if include_future_orders, limits, config)
baseStateHash = CanonicalHasher.hash(base)
changed = ChangeApplier.apply(base, scenario.changes in ordinal order)       // sets appliedChanges
SnapshotValidator.validate(changed)                                          // errors -> 422
inputHash = CanonicalHasher.hash({ snapshot: changed, algorithmVersion })
```

Only `ACTIVE` pools are included. Windows come from `resource_availability` (spec 03).
`baseStateHash` covers configuration as well as work in progress: two runs are "same starting state" only if
both the reconstructed WIP and the captured configuration match. Editing a pool between two runs therefore
changes the hash, and spec 08 will then require explicit consent to compare them.

## API

`POST /api/scenarios`

```json
{ "factoryId": "…", "name": "Add two polishing machines", "description": "…",
  "forkAt": "2026-01-12T08:00:00Z", "parentScenarioId": null, "includeFutureOrders": true }
```

`ScenarioResponse`

```json
{ "id": "…", "factoryId": "…", "name": "…", "description": "…", "parentScenarioId": null,
  "forkAt": "…", "includeFutureOrders": true, "baseline": false,
  "changes": [ { "id": "…", "ordinal": 1, "changeType": "RESOURCE_CAPACITY", "inherited": false,
                 "payload": { "resourceId": "…", "mode": "DELTA", "value": 2 },
                 "summary": "Polishing wheels: capacity +2 units" } ],
  "latestRun": { "runId": "…", "status": "COMPLETED", "finishedAt": "…" },
  "version": 4, "createdAt": "…", "updatedAt": "…" }
```

`baseline` is derived (`changes` is empty). `summary` is a server-generated plain-language description of
the change, built from the payload and current names; it contains no simulated numbers.

`POST /api/scenarios/{id}/validate` → 200

```json
{ "valid": true, "errors": [], 
  "warnings": [ { "changeId": "…", "code": "NO_EFFECT", "message": "…" } ],
  "preview": {
    "pools":  [ { "resourceId": "…", "name": "Polishing wheels", "capacityBefore": 6, "capacityAfter": 8 } ],
    "stages": [ { "stageId": "…", "code": "CUTTING", "stonesPerHourBefore": "20.0000", "stonesPerHourAfter": "20.0000",
                  "maxSlotsBefore": 4, "maxSlotsAfter": 2 } ],
    "weeklyWorkingSecondsBefore": 172800, "weeklyWorkingSecondsAfter": 172800,
    "unstartedStonesBefore": "1820.0000", "unstartedStonesAfter": "1820.0000" },
  "baseStateHash": "…", "inputHash": "…" }
```

## Error cases

| Case | Status |
|---|---|
| Invalid payload, foreign or unknown resource/stage/parent, `forkAt` out of range | 400 |
| Duplicate name, stale version, changing `forkAt` after a run exists | 409 |
| Snapshot invalid at submission (spec 07 calls `assemble`) | 422 with the validator's errors |
| Unknown scenario or change | 404 |

## Testing

- `ChangePayloadsTest`: valid and invalid payload per type; round trip JSON → record → JSON.
- `ChangeApplierTest`: each type in isolation with exact expected values (including `PERCENT` floor, demand scaling at scale 4, schedule union); ordering (capacity `+2` then `-30%` differs from the reverse).
- jqwik `ChangeApplierProperties`: the input snapshot object is never mutated; applying an empty list returns an equal snapshot with equal hash; capacity is never negative; a `DEMAND_VOLUME` change never alters batches already started.
- `SnapshotAssemblerIT`: assemble against seeded history; assert sort order, that completed batches are excluded, future orders included or excluded by flag, and equal `baseStateHash` for two scenarios with the same fork.
- `ScenarioApiIT`: CRUD, branching copies changes and marks `inherited`, parent edit does not affect child, ordinal renumbering, optimistic locking, validate endpoint persists nothing.
- `ScenarioIsolationIT`: after assembling and applying changes, live `resource`, `workflow_stage`, `working_shift`, `production_batch` rows are unchanged (row checksum).
