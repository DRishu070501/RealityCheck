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
  "ai": { "enabled": false, "provider": "none" },
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
{ "factoryId": "...", "forkAt": "2026-11-14T08:45:24Z", "endsAt": "2026-11-23T05:51:13Z",
  "algorithmVersion": "1.0.0", "terminationReason": "COMPLETED", "eventsProcessed": 412,
  "summary": { "elapsedSeconds": 767149, "workingSeconds": 195300, "processingSeconds": 1038000,
               "waitingWorkingSeconds": 210400, "waitingCalendarSeconds": 640200,
               "inputStones": "2010.0000", "outputStones": "1938.9432", "outputStonesRounded": "1939",
               "stoneYield": "0.964648", "throughputStonesPerHour": "35.8419",
               "machineUtilization": "0.331118", "employeeUtilization": "0.387641",
               "cost": { "employee": "...", "machine": "...", "fixed": "...", "total": "427097.81",
                         "perOutputStone": "220.2744", "currencyCode": "INR" },
               "ordersTotal": 18, "ordersCompleted": 18, "ordersDelayed": 8, "ordersWithDueDate": 18,
               "onTimeDeliveryRate": "0.555556" },
  "stages": [ { "code": "POLISHING", "name": "Polishing", "sequence": 6, "jobsCompleted": 21,
                "meanWaitWorkingSeconds": "37440.0000", "maxQueueLength": 9, "...": "...",
                "bottleneck": { "limitingPoolName": "Polishing wheels", "limitingPoolUtilization": "0.810000",
                                "waitSignal": "1.000000", "queueSignal": "1.000000",
                                "saturation": "0.709010", "blockedShare": "...", "score": "0.890970" } } ],
  "pools": [ { "name": "Polishing wheels", "kind": "MACHINE", "busyUnitSeconds": 0, "availableUnitSeconds": 0,
               "utilization": "...", "overcommitted": false, "cost": "..." } ],
  "orders": [ { "orderNumber": "SDW-0031", "completesAt": "...", "dueAt": "...", "outcome": "LATE", "delayed": true } ],
  "bottleneck": { "stageCode": "POLISHING", "stageName": "Polishing", "reasonCode": "CONSTRAINED_POOL_WITH_QUEUE",
                  "explanation": "Polishing is the bottleneck: work waited behind full slots for 71% of the time the stage had work (mean wait 10.4 working hours, queue up to 9); its most used pool, Polishing wheels, was at 81% over the whole run.",
                  "scoreThreshold": "0.50", "saturationThreshold": "0.50" },
  "unfinished": [], "source": "SIMULATED" }
```

(Figures above are from one run of the demonstration factory and change with the seeding day.)

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

## Planned endpoints

Not implemented yet. Shapes are specified in `.kiro/specs/`; the table shows where each group lands.

| Group | Endpoints | Milestone |
|---|---|---|
| Scenarios | `GET/POST /api/scenarios`, `GET/PATCH /api/scenarios/{id}`, `POST /api/scenarios/{id}/changes`, `PUT/DELETE /api/scenarios/{id}/changes/{changeId}` | 5 |
| Simulation runs | `POST /api/scenarios/{id}/simulate`, `GET /api/scenarios/{id}/simulations`, `GET /api/simulations/{runId}`, `.../results`, `.../stages` | 5 |
| Comparison | `POST /api/simulations/compare`, `GET /api/scenarios/{id}/comparisons` | 5 |
| AI | `POST /api/ai/insights`, `POST /api/ai/ask`, `GET /api/ai/insights/{runId}` | 7 |
