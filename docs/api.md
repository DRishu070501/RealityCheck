# API

The live, authoritative reference is generated from the code: Swagger UI at
`http://localhost:8080/swagger-ui.html`, OpenAPI document at `/v3/api-docs`. This page records the
conventions every endpoint follows and lists what is implemented.

## Conventions

- Base path `/api`. Requests and responses are JSON. Unknown request properties are rejected.
- Timestamps are ISO-8601 UTC (`2026-03-02T04:30:00Z`). Durations are integer seconds, with the unit in the
  field name (`setupSeconds`).
- Identifiers are UUIDs.
- A single resource is returned as the object. Creation answers `201` with a `Location` header. Simulation
  submission answers `202`.

### Lists

```json
{ "items": [], "page": 0, "size": 50, "totalItems": 0, "totalPages": 0 }
```

`page` is zero-based. `size` defaults to 50 and is capped at 200.

### Errors

Every error is an RFC 9457 problem document with content type `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/api/resources",
  "errors": [{ "field": "capacity", "message": "must be greater than or equal to 0" }]
}
```

`errors` is present only for validation failures and is sorted by field. A 409 caused by an out-of-date
`version` carries `"code": "STALE_VERSION"`, so a client can offer to reload without reading the message. Responses never contain stack
traces, exception class names or database messages.

| Status | Meaning |
|---|---|
| 400 | Malformed JSON, or a field failed validation |
| 404 | No such resource or path |
| 409 | Conflict: duplicate, or the record changed since it was read |
| 422 | Valid request that a business rule or state transition forbids |
| 429 | Simulation queue is full; retry later |
| 500 | Unexpected failure; details are in the server log only |

### CORS

Browser calls to `/api/**` are accepted only from the origins in `CORS_ALLOWED_ORIGINS`.

## Implemented endpoints

### `GET /api/system/info`

What the backend is connected to. If the database is unreachable the call still answers `200`, with
`database.status` `"DOWN"` and null product and version.

```json
{
  "application": "RealityCheck",
  "version": "0.1.0",
  "serverTime": "2026-11-14T05:54:59Z",
  "database": { "status": "UP", "product": "PostgreSQL", "version": "17.2" },
  "migrations": { "appliedCount": 1, "currentVersion": "1" },
  "simulation": { "algorithmVersion": "1.0.0" },
  "ai": { "enabled": false, "provider": "template", "model": null, "available": true },
  "demo": { "resetEnabled": true }
}
```

### Factory

A factory carries its time zone, currency and weekly working calendar. `factoryId` on the factory-scoped
list endpoints below may be omitted while exactly one factory exists; with none or several it is required.

| Endpoint | Purpose |
|---|---|
| `GET /api/factories` | List, ordered by name |
| `GET /api/factories/{id}` | One factory with its calendar and configuration counts |
| `POST /api/factories` | Create a factory with its calendar |

```json
{
  "id": "3e56055a-c69d-3e33-9ffa-c6844e063ed2",
  "name": "Surat Diamond Works",
  "description": "Fictional demonstration factory. All capacities, rates, yields and costs are synthetic.",
  "timeZone": "Asia/Kolkata",
  "currencyCode": "INR",
  "demo": true,
  "calendar": {
    "shifts": [
      { "id": "...", "dayOfWeek": 1, "startTime": "09:00", "endTime": "18:00",
        "breaks": [{ "startTime": "13:00", "endTime": "14:00" }] }
    ],
    "weeklyWorkingSeconds": 172800
  },
  "counts": { "resources": 14, "workflows": 1 },
  "version": 0,
  "createdAt": "2026-01-05T03:30:00Z",
  "updatedAt": "2026-01-05T03:30:00Z"
}
```

- `dayOfWeek` is ISO: 1 is Monday, 7 is Sunday. Times are `HH:mm`, local to `timeZone`. A day with no shift
  is closed.
- A shift must end after it starts and stay within one day. Shifts on the same day must not overlap. Breaks
  must lie inside their shift and must not overlap.
- `weeklyWorkingSeconds` is shift time minus breaks.
- `counts` has one key per kind of thing: `orders`, `resources`, `workflows`.
- `demo` is true only for the seeded demonstration factory and cannot be set through the API.

### Resources

A resource is a **pool** of interchangeable units: machines of one type or employees with one skill.

| Endpoint | Purpose |
|---|---|
| `GET /api/resources?factoryId=&kind=` | List: machines first, then employees, each by name. `kind` is `MACHINE` or `EMPLOYEE`. |
| `GET /api/resources/{id}` | One pool |
| `POST /api/resources` | Create a pool |
| `PUT /api/resources/{id}` | Update a pool |
| `GET /api/resources/{id}/availability?from=&to=` | Dated capacity windows and effective capacity |
| `POST /api/resources/{id}/availability` | Record a capacity window |

```json
{
  "id": "...", "factoryId": "...",
  "kind": "MACHINE", "category": "POLISHING_WHEEL", "name": "Polishing wheels",
  "capacity": 6, "hourlyCost": "140.00", "status": "ACTIVE",
  "requiredByStages": [
    { "stageId": "...", "stageCode": "POLISHING", "stageName": "Polishing", "sequence": 6, "unitsPerSlot": 1 }
  ],
  "version": 0, "createdAt": "...", "updatedAt": "..."
}
```

| Field | Unit and meaning |
|---|---|
| `capacity` | Number of units in the pool |
| `hourlyCost` | Factory currency per unit per hour, as a decimal string |
| `requiredByStages` | Stages of the factory's active workflow that use this pool |
| `version` | Send the value you last read with `PUT`; a stale value answers 409 |

`PUT` takes `name`, `category`, `capacity`, `hourlyCost`, `status` and `version`, all required. `kind` and
`factoryId` cannot be changed. Setting `status` to `INACTIVE` answers 409 while the active workflow requires
the pool.

**Availability.** A window is the half-open period `[startsAt, endsAt)` with a non-zero `capacityDelta`;
negative means units unavailable. Windows are never edited or deleted: counter a mistake with the opposite
delta over the same period. A window that removes units also appends a `RESOURCE_UNAVAILABLE` event to the
operational history, in the same transaction.

```json
{
  "resourceId": "...", "baseCapacity": 6,
  "from": "2026-01-07T00:00:00Z", "to": "2026-01-08T00:00:00Z",
  "windows": [
    { "id": "...", "startsAt": "2026-01-07T03:30:00Z", "endsAt": "2026-01-07T12:30:00Z",
      "capacityDelta": -2, "reason": "Scheduled wheel maintenance (synthetic)", "createdAt": "..." }
  ],
  "timeline": [
    { "from": "2026-01-07T00:00:00Z", "to": "2026-01-07T03:30:00Z", "capacity": 6 },
    { "from": "2026-01-07T03:30:00Z", "to": "2026-01-07T12:30:00Z", "capacity": 4 },
    { "from": "2026-01-07T12:30:00Z", "to": "2026-01-08T00:00:00Z", "capacity": 6 }
  ]
}
```

`timeline` is contiguous, covers the range exactly, and never goes below zero. Without `from` and `to` the
range spans all of the pool's windows; a pool with no windows then has an empty timeline.

### Workflow

A workflow is an ordered list of stages. A factory has at most one `ACTIVE` workflow.

| Endpoint | Purpose |
|---|---|
| `GET /api/workflows?factoryId=` | List; the active workflow first |
| `GET /api/workflows/{id}` | One workflow with its stages in sequence order |
| `GET /api/workflows/{id}/stages` | The stages alone, in the list envelope |
| `POST /api/workflows` | Create a workflow with all stages and requirements |

```json
{
  "id": "...", "code": "POLISHING", "name": "Polishing", "description": "...", "sequence": 6,
  "stonesPerHour": "6.0000", "setupSeconds": 900,
  "stoneYield": "0.990000", "caratYield": "0.820000", "fixedCostPerJob": "150.00",
  "requirements": [
    { "resourceId": "...", "resourceName": "Polishing wheels", "kind": "MACHINE", "unitsPerSlot": 1, "poolCapacity": 6 },
    { "resourceId": "...", "resourceName": "Polishers", "kind": "EMPLOYEE", "unitsPerSlot": 2, "poolCapacity": 12 }
  ],
  "maxSlots": 6
}
```

| Field | Unit and meaning |
|---|---|
| `sequence` | Position in the workflow. Sequences are exactly 1 to N. |
| `stonesPerHour` | Stones per hour **per processing slot** |
| `setupSeconds` | Seconds per job |
| `stoneYield`, `caratYield` | Fraction of stones, and of carat weight, that leaves the stage; greater than 0, at most 1 |
| `fixedCostPerJob` | Factory currency per job |
| `unitsPerSlot` | Units of the pool one processing slot needs |
| `maxSlots` | Jobs the stage can run at once at base capacity: the smallest `floor(poolCapacity / unitsPerSlot)`. `null` when the stage needs no resources. |

Decimals are strings so that no precision is lost in JavaScript; requests accept strings or numbers. Stage
codes are upper-case letters, digits and underscores, unique in the workflow. Each requirement must name an
active pool of the same factory, once per stage.

Queue length per stage comes from `GET /api/factories/{id}/state`. Utilization and average duration come
from simulation runs (Milestone 4).

### Orders

An order is the customer commitment; its **batches** are the parcels that move through the workflow. Stone
count and carat weight are separate quantities and are never combined.

| Endpoint | Purpose |
|---|---|
| `GET /api/orders?factoryId=&status=&delayed=` | List, earliest due first; orders without a due date last |
| `GET /api/orders/{id}` | `{ order, batches, events }`: the order, its batches, its full event history |
| `POST /api/orders` | Place an order; each batch is queued at the first stage |

```json
{ "factoryId": "...", "orderNumber": "SDW-0048", "customerRef": "Demo buyer A", "priority": 3,
  "placedAt": "2026-11-14T04:00:00Z", "dueAt": "2026-11-22T04:00:00Z",
  "batches": [ { "stoneCount": 90, "roughCarats": "117.0000" } ] }
```

`priority` is 1 to 5; 5 is the most urgent. `placedAt` defaults to now and may not be in the future; `dueAt`
is optional and must be after `placedAt`. The factory must have an active workflow (422 otherwise).

```json
{ "id": "...", "factoryId": "...", "orderNumber": "SDW-0048", "customerRef": "Demo buyer A",
  "priority": 3, "status": "IN_PROGRESS", "placedAt": "...", "dueAt": "...", "completedAt": null,
  "delayed": false, "batchCount": 1, "batchesCompleted": 0,
  "totalStones": 90, "totalRoughCarats": "117.0000", "source": "ACTUAL" }
```

| Field | Meaning |
|---|---|
| `status` | `PENDING` until a batch starts; `IN_PROGRESS` until every batch has finished the last stage; then `COMPLETED`; `DISPATCHED` once handed over |
| `delayed` | Finished after `dueAt`, or unfinished and past `dueAt` now |
| `totalStones`, `totalRoughCarats` | What the order started with, summed over batches |

A batch reports `stoneCount` and `roughCarats` (what it started with), `currentStones` and `currentCarats`
(the output of its last completed stage), its current stage, and `QUEUED`, `IN_PROGRESS` or `COMPLETED`.

### Events

The operational history is **append-only**. A database trigger rejects any `UPDATE` or `DELETE` on
`operational_event`; a mistake is fixed by appending a `CORRECTION`.

| Endpoint | Purpose |
|---|---|
| `POST /api/events` | Record a stage start, a stage completion, a dispatch, or a correction |
| `GET /api/events` | The raw log, corrections included, oldest first |

Every event has `occurredAt` (when it happened) and `recordedAt` (when it was written). `occurredAt`
defaults to now and may not be in the future.

| `eventType` | Send | Effect |
|---|---|---|
| `STAGE_STARTED` | `batchId` | The batch moves from queued to in progress at its current stage |
| `STAGE_COMPLETED` | `batchId`, `stoneQuantity`, `caratQuantity`, optional `durationSeconds` | Records what left the stage. The system then records `STAGE_QUEUED` at the next stage, or finishes the batch; when the last batch finishes it records `ORDER_COMPLETED`. |
| `ORDER_DISPATCHED` | `orderId` | A completed order is handed over |
| `CORRECTION` | `correctsEventId`, and either `voids: true` or replacement `stoneQuantity` / `caratQuantity` / `durationSeconds` | See below |

`ORDER_CREATED`, `STAGE_QUEUED`, `ORDER_COMPLETED` and `RESOURCE_UNAVAILABLE` are recorded by the system and
answer 400 if posted. `stageId` is optional; when sent it must be the batch's current stage.

**Batch state machine.** Anything else answers 422 with the reason.

| Batch is | Accepts | Becomes |
|---|---|---|
| queued at stage *s* | `STAGE_STARTED` | in progress at *s* |
| in progress at *s* | `STAGE_COMPLETED` | queued at *s*+1, or completed if *s* is the last stage |
| completed | nothing | |

An event may not occur before the batch's previous event, and a stage may not put out more stones or
carats than entered it. Concurrent events for one batch are serialised by a row lock: of two simultaneous
starts, exactly one succeeds.

**Corrections.** A correction either replaces the output or duration of a `STAGE_COMPLETED`, or voids a
`STAGE_STARTED` or `STAGE_COMPLETED`. The original row is never changed. After a correction the batch's
whole history must still be possible, otherwise it is refused: an output cannot be corrected below what a
later stage already produced, a step that later work depends on cannot be voided, and a finished batch
cannot be reopened. Voiding a completion also voids the queue event that followed from it. Corrections
cannot change when an event occurred.

In responses, `stoneQuantity`, `caratQuantity` and `durationSeconds` are the **effective** values (the
latest correction's, if any). `corrected: true` comes with `original`, the values first recorded.
`voided: true` marks an event a correction cancelled.

### Timeline

| Endpoint | Purpose |
|---|---|
| `GET /api/timeline` | What happened, as now known: corrections applied, voided events left out, newest first |
| `GET /api/timeline/summary?bucket=DAY` | Event counts per day or `HOUR` of factory-local time |

Both `/api/events` and `/api/timeline` accept `factoryId`, `orderId`, `batchId`, `stageId`, `type`, `from`,
`to` (inclusive instants), `order` (`asc` or `desc`), `page` and `size`. The timeline also takes
`includeVoided=true`. The timeline never lists `CORRECTION` rows; it shows their effect on the events they
target. Each item carries `orderNumber`, `batchNumber`, `stageCode`, `stageName` and `resourceName` where
they apply, and `source: "ACTUAL"`.

Summary buckets start at local midnight (or the local hour) in the factory's time zone and are returned as
UTC instants; buckets with no events are absent.

### Factory state and overview

| Endpoint | Purpose |
|---|---|
| `GET /api/factories/{id}/state?at=` | The factory as it stood at an instant (default now), rebuilt from the event history |
| `GET /api/factories/{id}/overview` | Order counts, stage queues, units in use, 14 days of output and recent events, as of now |

`state` replays every batch's events up to `at`. It is read-only and writes nothing. Corrections apply
whenever they were recorded, so the answer is what is **now known** about that moment. Before the first
event the state is empty.

```json
{ "factoryId": "...", "at": "2026-11-10T08:00:00Z", "source": "ACTUAL",
  "orders": { "PENDING": 0, "IN_PROGRESS": 16, "COMPLETED": 6, "DISPATCHED": 14 },
  "stages": [ { "stageId": "...", "code": "POLISHING", "name": "Polishing", "sequence": 6,
                "queued": 4, "inProgress": 6, "completed": 31 } ],
  "batches": [ { "batchId": "...", "orderId": "...", "orderNumber": "SDW-0031", "batchNumber": 1, "priority": 3,
                 "status": "QUEUED", "stageId": "...", "stageCode": "POLISHING", "stageSequence": 6,
                 "inputStones": "88.0000", "inputCarats": "63.1120",
                 "waitingSince": "2026-11-10T05:12:40Z", "startedAt": null, "completedAt": null } ],
  "resources": [ { "resourceId": "...", "name": "Polishing wheels", "kind": "MACHINE",
                   "baseCapacity": 6, "capacity": 6, "unitsInUse": 6 } ] }
```

| Field | Meaning |
|---|---|
| `stages[].queued`, `inProgress`, `completed` | Batches waiting at, being worked at, and already through the stage |
| `batches[].inputStones`, `inputCarats` | What the batch holds on entering its current stage; its final output once completed |
| `resources[].capacity` | Units available at `at`, after availability windows; `baseCapacity` is the configured number |
| `resources[].unitsInUse` | Units held by batches in progress at `at` |

`overview` adds `orders` (`total`, `active`, `pending`, `inProgress`, `completed`, `dispatched`, `delayed`),
`machines` and `employees` (`inUse`, `capacity`: a snapshot of this instant, not utilization over time),
`dailyOutput` (batches, stones and carats that finished the last stage per factory-local day, oldest first)
and `recentEvents` (the ten newest timeline items).

### Baseline simulation

| Endpoint | Purpose |
|---|---|
| `GET /api/factories/{id}/baseline-simulation?at=` | Run the simulation engine on the factory as recorded, from an instant (default now), changing nothing and storing nothing |

The engine starts from the state reconstructed at `at`: batches in progress keep their units and only their
remaining work is simulated, queued batches keep their place, and orders placed after `at` (if the history
knows of them) arrive at the first stage when they were placed. It runs until every batch has finished or a
limit is reached (180 simulated days, one million events). `at` may not be in the future (400). A factory
whose configuration cannot be simulated, for example a stage needing more units than a pool ever has,
answers 422 with every problem listed.

Every number in the response comes from the deterministic engine and is labelled `source: "SIMULATED"`; it is
an estimate under the rules in `.kiro/steering/simulation-rules.md`, not a forecast guarantee. The same factory
data, `at` and `algorithmVersion` always give the same response.

```json
{
  "factoryId": "...",
  "forkAt": "2026-11-14T08:46:34Z",
  "endsAt": "2026-11-23T05:51:13Z",
  "algorithmVersion": "1.0.0",
  "terminationReason": "COMPLETED",
  "eventsProcessed": 188,
  "summary": {
    "elapsedSeconds": 767079,
    "workingSeconds": 194679,
    "processingSeconds": 1389008,
    "waitingWorkingSeconds": 747811,
    "waitingCalendarSeconds": 2893411,
    "inputStones": "2022.0000",
    "outputStones": "1938.9352",
    "outputStonesRounded": "1939",
    "stoneYield": "0.958919",
    "throughputStonesPerHour": "35.8547",
    "machineUtilization": "0.331014",
    "employeeUtilization": "0.387536",
    "cost": {
      "employee": "363400.79",
      "machine": "55639.85",
      "fixed": "7880.00",
      "total": "426920.64",
      "perOutputStone": "220.1830",
      "currencyCode": "INR"
    },
    "ordersTotal": 18,
    "ordersCompleted": 18,
    "ordersDelayed": 8,
    "ordersWithDueDate": 18,
    "onTimeDeliveryRate": "0.555556"
  },
  "stages": [
    {
      "code": "POLISHING",
      "name": "Polishing",
      "sequence": 6,
      "jobsCompleted": 20,
      "meanWaitWorkingSeconds": "37390.5500",
      "maxQueueLength": 9,
      "...": "...",
      "bottleneck": {
        "limitingPoolName": "Polishing wheels",
        "limitingPoolUtilization": "0.813127",
        "waitSignal": "1.000000",
        "queueSignal": "1.000000",
        "saturation": "0.708900",
        "blockedShare": "0.673298",
        "score": "0.890929"
      }
    }
  ],
  "pools": [
    {
      "name": "Polishing wheels",
      "kind": "MACHINE",
      "busyUnitSeconds": 949793,
      "availableUnitSeconds": 1168074,
      "utilization": "0.813127",
      "overcommitted": false,
      "cost": "36936.39"
    }
  ],
  "orders": [
    {
      "orderNumber": "SDW-0045",
      "completesAt": "2026-11-23T05:51:13Z",
      "dueAt": "2026-11-21T07:30:00Z",
      "outcome": "LATE",
      "delayed": true
    }
  ],
  "bottleneck": {
    "stageCode": "POLISHING",
    "stageName": "Polishing",
    "reasonCode": "CONSTRAINED_POOL_WITH_QUEUE",
    "explanation": "Polishing is the bottleneck: work waited behind full slots for 71% of the time the stage had work (mean wait 10.4 working hours, queue up to 9); its most used pool, Polishing wheels, was at 81% over the whole run.",
    "scoreThreshold": "0.50",
    "saturationThreshold": "0.50"
  },
  "unfinished": [],
  "source": "SIMULATED"
}
```

(A real response from the demonstration factory, trimmed to one stage, pool and order. The figures change with the day the demo was seeded.)

| Field | Meaning |
|---|---|
| `elapsedSeconds` | Fork to last completion in calendar time, nights and closed days included |
| `workingSeconds` | Factory working time in that window |
| `processingSeconds`, `waitingWorkingSeconds` | Summed over jobs, in working time; so they can exceed `elapsedSeconds` |
| `outputStones` | Expected stones finishing the last stage, unrounded (yield is applied exactly); `outputStonesRounded` is for display |
| `machineUtilization`, `employeeUtilization` | Busy over available unit-time, capacity-weighted; `null` when nothing was available |
| `cost.employee` | Scheduled employee capacity over the run's working time: idle people are paid |
| `cost.machine` | Machine unit-hours actually run |
| `cost.perOutputStone` | Compare this as well as `total`: runs of different length accrue cost over different periods |
| `stages[].bottleneck.saturation` | Of the time the stage had work, the share spent with work waiting behind full slots |
| `bottleneck.reasonCode` | `CONSTRAINED_POOL_WITH_QUEUE`, `NO_SUSTAINED_QUEUE`, `NO_CLEAR_BOTTLENECK` or `NO_WORK` |
| `bottleneck.explanation` | One sentence assembled from the evidence by fixed rules, **not** by AI |
| `terminationReason` | `COMPLETED`, or `HORIZON_REACHED` / `EVENT_LIMIT_REACHED` with the remaining batches in `unfinished` |

This endpoint is the engine's read-only entry point until scenarios exist. Stored, asynchronous runs with
changes applied arrive in Milestone 5 (`POST /api/scenarios/{id}/simulate`).

### Scenarios

A scenario is a named "what if": a fork instant in recorded history plus an ordered list of changes.
Scenarios never touch live configuration or history; changes are applied to a copy (the input snapshot)
when a scenario is validated or simulated.

| Endpoint | Purpose |
|---|---|
| `GET /api/scenarios?factoryId=` | List, oldest first, with `changeCount`, `baseline` and `latestRun` |
| `POST /api/scenarios` | Create. `forkAt` must lie between the factory's first recorded event and now. With `parentScenarioId` the parent's fork and changes are copied once (changes marked `inherited: true`) |
| `GET /api/scenarios/{id}` | The scenario and its changes in order, each with a plain-language `summary` |
| `PATCH /api/scenarios/{id}` | `name`, `description`, `forkAt`, `includeFutureOrders` with the current `version`; a stale version is 409 `STALE_VERSION`; moving the fork or changing `includeFutureOrders` once a run exists is 409 |
| `POST /api/scenarios/{id}/changes` | Append `{ "changeType", "payload" }`; 201 |
| `PUT /api/scenarios/{id}/changes/{changeId}` | Replace the payload; the type cannot change |
| `DELETE /api/scenarios/{id}/changes/{changeId}` | Remove; later changes are renumbered 1..n |
| `POST /api/scenarios/{id}/validate` | `valid`, `errors`, `warnings`, a before/after `preview` and both hashes. Stores nothing |

Every add, edit and delete re-applies the whole change list to the factory's configuration, so a change
that would make a later one impossible is refused with 400 naming `changes[n].payload.<field>`. Payload
errors name `payload.<field>`; a pool or stage that is not active in the scenario's factory is 400.

One example payload per change type:

```json
{ "changeType": "RESOURCE_CAPACITY",    "payload": { "resourceId": "…", "mode": "DELTA", "value": 2 } }
{ "changeType": "RESOURCE_UNAVAILABLE", "payload": { "resourceId": "…", "units": 5 } }
{ "changeType": "RESOURCE_DOWNTIME",    "payload": { "resourceId": "…", "from": "2026-11-10T03:30:00Z", "to": "2026-11-10T12:30:00Z" } }
{ "changeType": "DEMAND_VOLUME",        "payload": { "percent": 20 } }
{ "changeType": "PROCESSING_RATE",      "payload": { "stageId": "…", "mode": "PERCENT", "value": "-12.5" } }
{ "changeType": "WORKING_SCHEDULE",     "payload": { "mode": "ADD", "shifts": [ { "dayOfWeek": 1, "startTime": "18:00", "endTime": "22:00" } ] } }
```

Rules and rounding:

- `RESOURCE_CAPACITY`: `ABSOLUTE` (whole units, 0 or more), `DELTA` (non-zero whole units, never below 0) or
  `PERCENT` (-100 or more, **rounded down** to whole units so capacity is never overstated: 4 saws at -30%
  become 2).
- `RESOURCE_UNAVAILABLE`: takes `units` out from `from` (default: the fork) to `to` (default: the end of the
  simulation horizon). `to` must be after the fork.
- `RESOURCE_DOWNTIME`: every unit out for the period.
- `DEMAND_VOLUME`: multiplies stones and carats of every batch not yet started on its first stage at the fork
  (including later arrivals) by `(100 + percent) / 100`, exactly at scale 4, half-even; stones are not
  rounded to whole numbers. Range: above -100, at most 500, not zero.
- `PROCESSING_RATE`: `ABSOLUTE` (above 0) or `PERCENT` (above -100), scale 4, half-even.
- `WORKING_SCHEDULE`: `ADD` shifts to the calendar or `REPLACE` it; shifts may not overlap. Added shifts
  apply to the whole factory with every pool at its normal capacity.

Warnings do not block a run: `NO_EFFECT` (for example a percentage that rounds to the same capacity, or
demand when no batch is unstarted) and `EXCEEDS_CAPACITY` (more units made unavailable than the pool has).
Errors come from the engine's own input validation, for example `REQUIREMENT_EXCEEDS_CAPACITY` when a change
leaves a stage unable to run.

`baseStateHash` identifies the starting state (work in progress reconstructed at the fork and the captured
configuration) and is equal for scenarios with the same fork when nothing changed in between. `inputHash`
identifies the exact run input: the snapshot with its changes and the algorithm version.

### Simulation runs

| Endpoint | Purpose |
|---|---|
| `POST /api/scenarios/{id}/simulate?force=false` | Validate, store the input snapshot, answer **202** with `{ runId, scenarioId, status, reused, submittedAt }` and run in the background. Header `Idempotency-Key` (at most 100 characters): a repeated key returns the original run. A completed run with the same `inputHash` is returned with `reused: true` unless `force=true`. An invalid scenario is 422 with `errors`; no free worker slot is 429 with `Retry-After: 5` |
| `GET /api/scenarios/{id}/simulations` | The scenario's runs, newest first |
| `GET /api/simulations/{runId}` | Status (`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`), timing, hashes, termination reason, failure message |
| `GET /api/simulations/{runId}/results` | Summary, bottleneck, order outcomes, per-pool `resources` and unfinished batches; `source: "SIMULATED"`. 409 with `runStatus` until the run is `COMPLETED` |
| `GET /api/simulations/{runId}/stages` | Per-stage results with bottleneck evidence, in sequence order |
| `GET /api/simulations/{runId}/resources` | Per-pool utilization, capacity and cost, machines first |
| `GET /api/simulations/{runId}/events?stageId=&orderId=&page=&size=` | The simulated trace in order, paginated |

A run reads only its stored snapshot and writes only `simulation_*` tables. It either completes with every
result row or fails with none; a failure message never contains internals. A trigger makes a run's input
columns immutable, and result rows cannot be updated, so stored results are served unchanged and labelled
with the algorithm version that produced them. Runs left pending or running by a restart are marked
`FAILED` ("Interrupted by restart") at start-up. Workers: `realitycheck.simulation.threads` (2) and
`queue-capacity` (8).

A real run of the seeded baseline scenario (`GET /api/simulations/{runId}`, 14 November 2026):

```json
{ "runId": "e2d9ceaf-4cf5-4aab-bb6b-d360bd124ad3", "scenarioName": "Current factory operations",
  "status": "COMPLETED", "forkAt": "2026-11-09T18:03:14Z", "algorithmVersion": "1.0.0",
  "inputHash": "e01cf936448e523294485bc5094f7d12a4d2ea7db0fec795d92360f3bfbfcfef",
  "baseStateHash": "db7d9beec1b7e4bc474d33bc57391f64d23231cfee6ad8050530707747abb490",
  "terminationReason": "COMPLETED", "simulatedEndAt": "2026-11-24T04:47:22Z", "eventsProcessed": 534,
  "failureMessage": null, "submittedAt": "2026-11-14T18:03:39.291264Z",
  "startedAt": "2026-11-14T18:03:39.310034Z", "finishedAt": "2026-11-14T18:03:39.409285Z" }
```

The engine itself took 3 to 38 ms for each of the six demonstration scenarios in that session (logged as
`engineMillis`); submission to completion took about 0.1 s.

### Comparison

| Endpoint | Purpose |
|---|---|
| `POST /api/simulations/compare` | `{ "baselineRunId", "alternativeRunIds": [1..5], "allowDifferentBaseline": false, "includeActual": false }` |
| `GET /api/scenarios/{id}/comparisons` | The scenario's latest completed run against the newest matching run of every baseline scenario (one with no changes) that started from the same state. `baselineMissing: true` and `suggestedBaselineScenarioId` when none has been run |

Every value is read from stored results; the comparison only subtracts, with exact decimals. Runs must be
`COMPLETED` (409 otherwise) and from one factory (400). If an alternative started from a different fork or
`baseStateHash`, the answer is 422 `DIFFERENT_STARTING_STATE` unless `allowDifferentBaseline` is true, in
which case `sameStartingState` is false and each such run gets a warning. Other warnings:
`DIFFERENT_ALGORITHM_VERSION` and `INCOMPLETE_SIMULATION` (a run stopped on a limit). A run that is not
`COMPLETED` answers 409 with `runId` and `runStatus`.

**What actually happened.** With `includeActual: true`, `actual` holds what recorded history shows after the
fork for the work the baseline run simulated: the orders in the baseline run, and the batches that were
unfinished at the fork. It is read from the factory state rebuilt at the latest recorded event (`asOf`),
corrections applied. History stops at `asOf` while a simulation runs every order to completion, so these
figures are shown beside the simulated ones and **never subtracted from them**. `actual` is null when it was
not requested or nothing was recorded after the fork. A real example (the demonstration baseline, forked
five working days before seeding; 18 of the 29 orders omitted):

```json
"actual": { "source": "ACTUAL", "asOf": "2026-11-14T12:30:00Z", "ordersInScope": 29, "ordersCompleted": 11,
            "outputStones": "1279.0000", "outputCarats": "820.8626",
            "orders": [ { "orderId": "81f770cc-…", "orderNumber": "SDW-0030", "completedAt": "2026-11-14T09:14:36Z" },
                        { "orderId": "97e608c9-…", "orderNumber": "SDW-0045", "completedAt": null } ] }
```

For each alternative: 16 summary `metrics` (each with `baseline`, `alternative`, `absolute`, `percent`,
`unit`, `betterWhen` and `direction`), the `bottleneck` change (`SAME`, `MOVED`, `RESOLVED`, `EMERGED`,
`NONE`) with both stages' scores in both runs, and per-stage, per-pool and per-order differences. Applied
changes come from each run's stored snapshot, so editing a scenario later does not rewrite a comparison.
Utilization has no `betterWhen`: higher is not inherently better. Runs of different length accrue cost over
different periods, so read total cost together with cost per output stone. "Improved" describes how a
metric moved under the simulation's assumptions, not a recommendation.

Excerpts from a real comparison (baseline against "Introduce an additional shift", same session):

```json
{ "source": "SIMULATED", "currencyCode": "INR", "sameStartingState": true, "forkAt": "2026-11-09T18:03:14Z",
  "warnings": [],
  "alternatives": [ {
    "run": { "scenarioName": "Introduce an additional shift", "algorithmVersion": "1.0.0",
             "terminationReason": "COMPLETED",
             "appliedChanges": [ { "ordinal": 1, "changeType": "WORKING_SCHEDULE",
                                   "summary": "Add shifts: Mon, Tue, Wed, Thu, Fri, Sat 18:00-22:00" } ] },
    "metrics": [
      { "key": "elapsedSeconds", "label": "Completion time", "unit": "seconds", "baseline": "1248248",
        "alternative": "834247", "absolute": "-414001", "percent": "-33.17", "betterWhen": "LOWER",
        "direction": "IMPROVED" },
      { "key": "cost.total", "label": "Total cost", "unit": "currency", "baseline": "856352.00",
        "alternative": "875469.83", "absolute": "19117.83", "percent": "2.23", "betterWhen": "LOWER",
        "direction": "WORSENED" },
      { "key": "machineUtilization", "label": "Machine utilization", "unit": "fraction", "baseline": "0.507217",
        "alternative": "0.467027", "absolute": "-0.040190", "percent": "-7.92", "betterWhen": null,
        "direction": null } ],
    "bottleneck": { "change": "SAME",
                    "baselineStage": { "code": "POLISHING", "baselineScore": "0.939906", "alternativeScore": "0.881322" } },
    "orders": [ { "orderNumber": "SDW-0030", "dueAt": "2026-11-15T11:30:00Z",
                  "baselineCompletionAt": "2026-11-23T07:24:46Z", "alternativeCompletionAt": "2026-11-14T14:35:36Z",
                  "differenceSeconds": -751750, "baselineOutcome": "LATE", "alternativeOutcome": "ON_TIME" } ] } ],
  "actual": null }
```

(Excerpt: other metrics, stages and resources omitted.)

### Demo

| Endpoint | Purpose |
|---|---|
| `POST /api/demo/reset` | Erase **all** data and re-create the demonstration factory. Answers `{ "factoryId", "resetAt" }`. |

The endpoint exists only when `REALITYCHECK_DEMO_RESET_ENABLED=true`; otherwise it answers 404.
`GET /api/system/info` reports the setting as `demo.resetEnabled`. See [demo-data.md](demo-data.md).

### Actuator

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall status, no component details |
| `GET /actuator/health/liveness` | Process is alive |
| `GET /actuator/health/readiness` | Ready to serve; used by the container health check |
| `GET /actuator/info` | Build information |

No other actuator endpoint is exposed.

### AI analyst

Explanations of stored simulation results. The content is tagged `source: "AI_INTERPRETATION"`; the
evidence items it cites are `SIMULATED` values quoted as stored. Every number in the text was checked against
the evidence before the response was returned. The answering provider is the built-in template analyst unless
a language model is configured (see [ai.md](ai.md)).

| Method and path | Purpose |
|---|---|
| `POST /api/ai/insights` | Explain a completed run: `{"runId", "baselineRunId"?, "type", "regenerate"?}` |
| `POST /api/ai/ask` | Answer a question about a run: `{"runId", "baselineRunId"?, "question"}`, question at most 500 characters |
| `GET /api/ai/insights/{runId}?type=&page=&size=` | Stored insights of the run, newest first (list envelope) |

`type` is `EXPLANATION`, `BOTTLENECK`, `RECOMMENDATIONS`, `DELAY_ANALYSIS`, `COMPARISON_SUMMARY` (needs
`baselineRunId`) or `EXECUTIVE_REPORT`. A stored insight for the same run, baseline, type and evidence is
returned without calling the provider unless `regenerate` is `true`. Questions are always answered afresh.

Errors: `400` for a missing `runId`, a question over 500 characters, `COMPARISON_SUMMARY` without a baseline,
or `type: "ANSWER"` (use `/ask`); `404` for an unknown run; `409` with `runStatus` for a run that is not
`COMPLETED`; `422` `DIFFERENT_STARTING_STATE` when the baseline started from another state; `429` with
`Retry-After` beyond `AI_REQUESTS_PER_MINUTE`. A failing or unreachable language model is **not** an error: the
response is `200` with `fallback: true`, `fallbackReason` (`VALIDATION_FAILED`, `PROVIDER_ERROR` or `TIMEOUT`)
and the template analyst's content.

A real answer from Claude Haiku 5.5 about the demonstration baseline run, with each list cut to its first item
and the evidence to one of its four items:

```http
POST /api/ai/ask
{"runId": "e2d9ceaf-4cf5-4aab-bb6b-d360bd124ad3", "question": "Which resource is underused?"}
```

```json
{
  "id": "605fc223-da1d-4a2b-9599-e28e73f64549",
  "runId": "e2d9ceaf-4cf5-4aab-bb6b-d360bd124ad3",
  "baselineRunId": null,
  "type": "ANSWER",
  "question": "Which resource is underused?",
  "source": "AI_INTERPRETATION",
  "provider": "anthropic",
  "model": "claude-haiku-5-5",
  "fallback": false,
  "fallbackReason": null,
  "inputTokens": 19298,
  "outputTokens": 858,
  "createdAt": "2026-11-15T09:54:22.718680Z",
  "content": {
    "summary": "The least used resource pools are Planning scanners, Intake clerks and Dispatch clerks among those with the lowest utilization figures. Intake clerks are at 5.2% utilization, the lowest of the pools with a stated utilization, and Dispatch clerks are at 8.8%. Planning scanners are at 21.6% and their idle unit time is 229 hours.",
    "keyChanges": [
      "Intake clerks (employee) utilization is 5.2% with idle unit time of 184 hours."
    ],
    "risks": [
      "Low utilization of these pools does not mean they can be cut, since they still carry cost, for example Planners cost INR 75,886 and Intake clerks cost INR 21,404."
    ],
    "bottleneckExplanation": "Polishing is the bottleneck, with its Polishing wheels pool at 89.6% utilization, so the low-use pools are not the constraint.",
    "recommendations": [
      {
        "title": "Review staffing of Intake clerks and Dispatch clerks",
        "rationale": "Both pools show very low utilization, so their hours could be checked against actual workload before any change is considered.",
        "metricRefs": [
          "run.resource.7b3d7dc8-39f1-3d4b-ba6e-29057ba04a35.utilization",
          "run.resource.56cfb1fc-f93c-3712-9f6e-cf339d88a825.utilization"
        ]
      }
    ],
    "metricRefs": [
      "run.resource.7b3d7dc8-39f1-3d4b-ba6e-29057ba04a35.utilization",
      "run.resource.56cfb1fc-f93c-3712-9f6e-cf339d88a825.utilization",
      "run.resource.fb27d6ed-71b6-39e8-8431-fc41f09c6056.utilization",
      "run.bottleneck.primaryStage"
    ],
    "assumptions": [
      "Utilization is taken as the share of the run's working time each pool was busy, as stated in the simulation."
    ],
    "limitations": [
      "The evidence gives utilization for each pool but does not say what cutting any pool would do to output or delivery."
    ]
  },
  "evidence": [
    {
      "key": "run.resource.7b3d7dc8-39f1-3d4b-ba6e-29057ba04a35.utilization",
      "label": "Intake clerks (employee): utilization",
      "value": "0.052250",
      "text": null,
      "unit": "fraction",
      "display": [
        "5.2%",
        "5%"
      ],
      "sourceRunId": "e2d9ceaf-4cf5-4aab-bb6b-d360bd124ad3",
      "stageId": null,
      "resourceId": "7b3d7dc8-39f1-3d4b-ba6e-29057ba04a35",
      "source": "SIMULATED"
    }
  ]
}
```

`inputTokens` and `outputTokens` cover every attempt sent to the language model; both are null for the template
analyst.
