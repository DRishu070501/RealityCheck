# 04 Operational Event History — Design

## Components

Package `com.realitycheck.production`:

| Class | Responsibility |
|---|---|
| `api.OrderController` | `GET /api/orders`, `POST /api/orders`, `GET /api/orders/{id}`. |
| `api.EventController` | `GET /api/events`, `POST /api/events`. |
| `api.*Request`, `*Response` | `CreateOrderRequest`, `CreateBatchRequest`, `OrderResponse`, `OrderDetailResponse`, `BatchResponse`, `AppendEventRequest`, `EventResponse`. |
| `service.OrderService` | Order creation (order + batches + initial events), reads, `delayed` derivation. |
| `service.EventAppendService` | The single write path for events: validate, append, emit follow-on events, update projections. |
| `service.EventValidator` | Reference checks and the batch state machine. |
| `service.BatchProjector` | Pure function: ordered effective events of one batch → `BatchProjection(status, stageId, stones, carats, queuedAt, startedAt)`. Used for incremental updates, rebuilds and by state reconstruction (spec 05). |
| `service.EffectiveEvents` | Pure function: raw events → effective events (corrections applied, voided removed). |
| `service.EventQueryService` | Filtered, paginated reads. |
| `domain.ProductionOrder`, `ProductionBatch`, `OperationalEvent`, `OrderStatus`, `BatchStatus`, `EventType` | Entities and enums. `OperationalEvent` has no setters and is mapped `@Immutable`. |
| `demo.ProductionSeedSection` (order 40) | Generates history through `OrderService` and `EventAppendService` with explicit timestamps. |

## Data model

```sql
production_order (
  id           uuid primary key,
  factory_id   uuid not null references factory(id),
  order_number varchar(40) not null,
  customer_ref varchar(120),
  priority     smallint not null check (priority between 1 and 5),     -- 5 = highest
  due_at       timestamptz,
  status       varchar(16) not null check (status in ('PENDING','IN_PROGRESS','COMPLETED','DISPATCHED')),
  completed_at timestamptz,
  placed_at    timestamptz not null,           -- business time of ORDER_CREATED
  version      bigint not null default 0,
  created_at   timestamptz not null,
  updated_at   timestamptz not null,
  unique (factory_id, order_number)
)

production_batch (
  id               uuid primary key,
  order_id         uuid not null references production_order(id),
  batch_number     integer not null check (batch_number >= 1),
  stone_count      integer not null check (stone_count >= 1),            -- initial, whole stones
  rough_carats     numeric(14,4) not null check (rough_carats > 0),      -- initial
  current_stones   numeric(14,4) not null,
  current_carats   numeric(14,4) not null,
  current_stage_id uuid references workflow_stage(id),                   -- null when finished
  status           varchar(16) not null check (status in ('QUEUED','IN_PROGRESS','COMPLETED')),
  version          bigint not null default 0,
  created_at       timestamptz not null,
  updated_at       timestamptz not null,
  unique (order_id, batch_number)
)

operational_event (
  id                uuid primary key,
  seq               bigint generated always as identity,
  factory_id        uuid not null references factory(id),
  order_id          uuid references production_order(id),
  batch_id          uuid references production_batch(id),
  stage_id          uuid references workflow_stage(id),
  resource_id       uuid references resource(id),
  event_type        varchar(32) not null,
  occurred_at       timestamptz not null,
  stone_quantity    numeric(14,4),
  carat_quantity    numeric(14,4),
  duration_seconds  bigint check (duration_seconds is null or duration_seconds >= 0),
  payload           jsonb not null default '{}'::jsonb,
  corrects_event_id uuid references operational_event(id),
  created_at        timestamptz not null,
  check ((event_type = 'CORRECTION') = (corrects_event_id is not null))
)
```

Indexes: `operational_event(factory_id, occurred_at, seq)`, `(batch_id, occurred_at, seq)`, `(order_id)`,
`(stage_id, occurred_at)`, `(corrects_event_id) where corrects_event_id is not null`;
`production_order(factory_id, status)`, `production_batch(order_id)`, `production_batch(current_stage_id)`.

Append-only trigger:

```sql
create function reject_operational_event_mutation() returns trigger as $$
begin
  raise exception 'operational_event is append-only' using errcode = '55000';
end $$ language plpgsql;

create trigger operational_event_append_only
  before update or delete on operational_event
  for each row execute function reject_operational_event_mutation();
```

`TRUNCATE` is deliberately not blocked; it is used only by the demo reset (spec 01).

Event types: `ORDER_CREATED`, `STAGE_QUEUED`, `STAGE_STARTED`, `STAGE_COMPLETED`, `RESOURCE_UNAVAILABLE`,
`ORDER_COMPLETED`, `ORDER_DISPATCHED`, `CORRECTION`.

Quantities on events: `STAGE_QUEUED` and `STAGE_STARTED` carry the batch's input quantities at that stage;
`STAGE_COMPLETED` carries the **output** quantities and `duration_seconds` (working seconds spent). Recorded
stones on real events are normally whole numbers, but the column is `numeric(14,4)` to match the engine.

## Batch state machine

State is `(stage, QUEUED | IN_PROGRESS)` or `COMPLETED`.

| Current | Event | Next |
|---|---|---|
| (none) | `STAGE_QUEUED` at stage 1 | (1, QUEUED) |
| (s, QUEUED) | `STAGE_STARTED` at s | (s, IN_PROGRESS) |
| (s, IN_PROGRESS) | `STAGE_COMPLETED` at s, s not last | follow-on `STAGE_QUEUED` at s+1 → (s+1, QUEUED) |
| (s, IN_PROGRESS) | `STAGE_COMPLETED` at s, s last | COMPLETED |

Anything else is 422. `occurredAt` must be greater than or equal to the batch's latest effective event.
Order status: `PENDING` until any batch has a `STAGE_STARTED`; `IN_PROGRESS` until all batches are
`COMPLETED`; then `COMPLETED` (with `completed_at` = the last batch's completion); `DISPATCHED` after
`ORDER_DISPATCHED`.

## Append algorithm (`EventAppendService.append`)

```
tx:
  lock the batch row (SELECT … FOR UPDATE) if batchId present, else the order row
  validate references and factory ownership                      -> 400
  if type == CORRECTION: applyCorrection (below); return
  load effective events for the batch; project current state
  validate transition and quantities                              -> 422 / 400
  insert event
  if STAGE_COMPLETED and not last stage: insert STAGE_QUEUED(next, same occurredAt, output quantities)
  if STAGE_COMPLETED on last stage and all sibling batches COMPLETED: insert ORDER_COMPLETED
  recompute batch projection (BatchProjector) and order status; save
```

The row lock serialises concurrent appends for one batch, so the state machine cannot be raced.

`applyCorrection`: insert the correction; compute the batch's effective event list including it; run the
whole list through the state machine and quantity checks; if invalid, throw (transaction rolls back, 422);
else overwrite the projections with the recomputed values.

`EffectiveEvents.of(raw)`: group corrections by `corrects_event_id`; for each original take the correction
with the highest `seq`; if it is voided drop the original; else overlay non-null replacement fields
(`occurredAt`, `stoneQuantity`, `caratQuantity`, `durationSeconds`); drop correction rows; sort by
`(occurredAt, seq)`.

## API

`POST /api/orders`

```json
{ "factoryId": "…", "orderNumber": "SDW-2026-0013", "customerRef": "Demo buyer", "priority": 3,
  "dueAt": "2026-02-02T12:30:00Z", "placedAt": "2026-01-20T04:00:00Z",
  "batches": [ { "stoneCount": 240, "roughCarats": "312.5000" } ] }
```

`placedAt` defaults to now. `OrderResponse`:

```json
{ "id": "…", "orderNumber": "SDW-2026-0013", "priority": 3, "status": "IN_PROGRESS",
  "dueAt": "…", "placedAt": "…", "completedAt": null, "delayed": false,
  "batchCount": 1, "totalStones": 240, "totalRoughCarats": "312.5000", "source": "ACTUAL" }
```

`GET /api/orders/{id}` adds `batches[]` (`id`, `batchNumber`, `stoneCount`, `roughCarats`, `currentStones`,
`currentCarats`, `currentStageId`, `currentStageCode`, `status`) and `events[]` (`EventResponse`).

`POST /api/events`

```json
{ "factoryId": "…", "eventType": "STAGE_COMPLETED", "batchId": "…", "stageId": "…",
  "occurredAt": "2026-01-21T07:15:00Z", "stoneQuantity": "238.0000", "caratQuantity": "301.2500",
  "durationSeconds": 9000, "payload": { "note": "two stones rejected" } }
```

For a correction: `{ "eventType": "CORRECTION", "correctsEventId": "…", "occurredAt": "…", "stoneQuantity": "239.0000", "payload": { "reason": "miscount" } }`.
`orderId` is derived from the batch when omitted. `payload` is limited to 8 KB.

`EventResponse`: all stored columns plus `stageCode`, `orderNumber`, `corrected` (boolean) and
`effective` (the overlaid values when corrected).

## Error cases

| Case | Status |
|---|---|
| Missing or foreign references, bad quantities, future `occurredAt`, bad correction target, oversize payload | 400 |
| Invalid transition, out-of-order time, correction that invalidates the sequence, no ACTIVE workflow | 422 |
| Duplicate order number | 409 |
| Unknown order | 404 |

## Testing

- `BatchProjectorTest` and `EffectiveEventsTest`: pure unit tests, including corrections of time and quantity, voiding, and latest-correction-wins.
- jqwik `BatchStateMachineProperties`: for any generated valid event sequence, the incremental projection equals the from-scratch projection; for any sequence with one event removed or swapped out of order, the validator rejects it.
- `EventAppendServiceTest` (Mockito): follow-on events, order completion with several batches, each rejection path.
- `OperationalEventIT` (Testcontainers): `UPDATE` and `DELETE` through JDBC raise the trigger error; concurrent `STAGE_STARTED` for one batch yields one success and one 422; order creation is atomic (failure inserting a batch leaves no order or events).
- `ProductionSeedIT`: seeded history replays cleanly through `BatchProjector` for every batch and matches stored projections; state mix as required.
