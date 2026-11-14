# 03 Resource Management — Design

## Components

Package `com.realitycheck.resource`:

| Class | Responsibility |
|---|---|
| `api.ResourceController` | `GET /api/resources`, `POST /api/resources`, `PUT /api/resources/{id}`, `GET` and `POST /api/resources/{id}/availability`. |
| `api.CreateResourceRequest`, `UpdateResourceRequest`, `ResourceResponse`, `CreateAvailabilityRequest`, `AvailabilityResponse`, `CapacityTimelineResponse` | DTO records. |
| `service.ResourceService` | Create, update with optimistic locking, deactivation guard. |
| `service.ResourceAvailabilityService` | Window creation, overlap query, effective-capacity timeline. |
| `service.ResourceReader` | Read-only view for other modules: `ResourceView(id, factoryId, kind, category, name, capacity, hourlyCost, status)` and `windows(resourceId, from, to)`. Implements `factory`'s `ResourceCounts`. |
| `service.CapacityTimeline` | Pure function: base capacity + windows → ordered list of `(from, to, capacity)` segments. |
| `domain.Resource`, `ResourceAvailability`, `ResourceKind`, `ResourceStatus` | Entities and enums. |
| `demo.ResourceSeedSection` (order 20) | Seeds pools and one downtime window. |

Two ports keep the dependency direction of architecture.md (`resource` depends only on `factory`):

- `resource.service.RequirementUsage` — interface implemented in `workflow`: "which stages of ACTIVE workflows
  require pool X". Used by the deactivation guard and by `unitsInUse`.
- `resource.service.ResourceEventSink` — interface implemented in `production`: append a
  `RESOURCE_UNAVAILABLE` event. Called inside the window-creation transaction.

## Data model

```sql
resource (
  id          uuid primary key,
  factory_id  uuid not null references factory(id),
  kind        varchar(16)  not null check (kind in ('MACHINE','EMPLOYEE')),
  category    varchar(60)  not null,         -- machine type or skill, e.g. 'POLISHING_WHEEL', 'POLISHER'
  name        varchar(120) not null,
  capacity    integer      not null check (capacity >= 0),          -- interchangeable units
  hourly_cost numeric(12,2) not null check (hourly_cost >= 0),      -- per unit per hour
  status      varchar(16)  not null check (status in ('ACTIVE','INACTIVE')),
  version     bigint       not null default 0,
  created_at  timestamptz  not null,
  updated_at  timestamptz  not null,
  unique (factory_id, name)
)

resource_availability (
  id             uuid primary key,
  resource_id    uuid not null references resource(id) on delete cascade,
  starts_at      timestamptz not null,
  ends_at        timestamptz not null,
  capacity_delta integer not null check (capacity_delta <> 0),      -- negative = units unavailable
  reason         varchar(200) not null,
  created_at     timestamptz not null,
  check (ends_at > starts_at)
)
```

Indexes: `resource(factory_id, kind)`, `resource_availability(resource_id, starts_at, ends_at)`.

Windows are half-open `[starts_at, ends_at)`, matching simulation-rules.md section 7. Windows are immutable
once created (no update or delete endpoint in the MVP); a mistaken window is countered with an opposite
delta over the same period.

## API

`POST /api/resources`

```json
{ "factoryId": "…", "kind": "MACHINE", "category": "POLISHING_WHEEL",
  "name": "Polishing wheels", "capacity": 6, "hourlyCost": "180.00" }
```

`ResourceResponse`

```json
{ "id": "…", "factoryId": "…", "kind": "MACHINE", "category": "POLISHING_WHEEL",
  "name": "Polishing wheels", "capacity": 6, "hourlyCost": "180.00", "status": "ACTIVE",
  "unitsInUse": 5, "unitsInUseSource": "ACTUAL",
  "requiredByStages": [ { "stageId": "…", "stageCode": "POLISHING", "unitsPerSlot": 1 } ],
  "version": 3, "createdAt": "…", "updatedAt": "…" }
```

`PUT /api/resources/{id}` — body: `name`, `category`, `capacity`, `hourlyCost`, `status`, `version`
(all required). Returns the updated `ResourceResponse`.

`POST /api/resources/{id}/availability`

```json
{ "startsAt": "2026-01-14T03:30:00Z", "endsAt": "2026-01-14T12:30:00Z",
  "capacityDelta": -2, "reason": "Wheel maintenance" }
```

`GET /api/resources/{id}/availability?from=&to=` (both optional; default the 30 days around now)

```json
{ "resourceId": "…", "baseCapacity": 6,
  "windows": [ { "id": "…", "startsAt": "…", "endsAt": "…", "capacityDelta": -2, "reason": "Wheel maintenance" } ],
  "timeline": [ { "from": "…", "to": "…", "capacity": 6 }, { "from": "…", "to": "…", "capacity": 4 } ] }
```

`POST …/availability` is not in the original endpoint list. It is added here because without it the only
source of downtime is the seed; architecture.md's list of additional endpoints should gain it when this spec
is implemented.

## CapacityTimeline algorithm

Input: `base`, windows, range `[from, to)`.

1. Collect boundary instants: `from`, `to`, and every window start or end inside the range. Sort, de-duplicate.
2. For each consecutive pair `(a, b)`: capacity = `max(0, base + sum(delta of windows with starts_at <= a and ends_at > a))`.
3. Merge adjacent segments with equal capacity.

The same function is used when assembling the simulation snapshot (spec 06), so the UI and the engine agree.

## unitsInUse

`production` exposes (spec 05) the current factory state: batches in progress per stage. `ResourceService`
does not query events itself. The controller composes: for each pool, sum over stages requiring it of
`inProgressJobs(stage) * unitsPerSlot`, through a `ResourceUsageProvider` interface declared in `resource`
and implemented in `production`. Until spec 05 is implemented the provider returns zero and the field is
omitted.

## Error cases

| Case | Status |
|---|---|
| Negative capacity or cost, zero delta, window end before start, attempt to change kind | 400 |
| Duplicate name, stale version, deactivating a pool required by an ACTIVE workflow | 409 |
| Unknown pool | 404 |

## Testing

- `CapacityTimelineTest`: no windows; one window inside; overlapping windows; floor at zero; window partly outside range; adjacent-segment merge. Property (jqwik): timeline segments are contiguous, cover the range exactly, and capacity is never negative.
- `ResourceServiceTest`: stale version → conflict; deactivation guard; immutable kind.
- `ResourceApiIT`: CRUD, filter by kind, optimistic locking with two concurrent updates, window creation appends exactly one `RESOURCE_UNAVAILABLE` event (after spec 04 exists).
- `ResourceSeedIT`: every demo stage requirement resolves; named pools exist.
