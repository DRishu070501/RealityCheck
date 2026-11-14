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

`errors` is present only for validation failures and is sorted by field. Responses never contain stack
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
- `counts` gains a key as each module is built (`orders` arrives in Milestone 3).
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
delta over the same period.

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

Queue length, utilization and average duration per stage are not part of this resource. They come from
recorded history (Milestone 3) and from simulation runs (Milestone 4).

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
| Factory state | `GET /api/factories/{id}/state?at=` | 5 |
| Production | `GET/POST /api/orders`, `GET /api/orders/{id}`, `GET/POST /api/events`, `GET /api/timeline` | 3 |
| Scenarios | `GET/POST /api/scenarios`, `GET/PATCH /api/scenarios/{id}`, `POST /api/scenarios/{id}/changes`, `PUT/DELETE /api/scenarios/{id}/changes/{changeId}` | 5 |
| Simulation | `POST /api/scenarios/{id}/simulate`, `GET /api/scenarios/{id}/simulations`, `GET /api/simulations/{runId}`, `.../results`, `.../stages` | 4, 5 |
| Comparison | `POST /api/simulations/compare`, `GET /api/scenarios/{id}/comparisons` | 5 |
| AI | `POST /api/ai/insights`, `POST /api/ai/ask`, `GET /api/ai/insights/{runId}` | 7 |
