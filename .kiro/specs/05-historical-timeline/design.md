# 05 Historical Timeline — Design

## Components

Package `com.realitycheck.production` (the tables are owned by spec 04):

| Class | Responsibility |
|---|---|
| `api.TimelineController` | `GET /api/timeline`, `GET /api/timeline/summary`. |
| `api.FactoryStateController` | `GET /api/factories/{id}/state`, `GET /api/factories/{id}/overview`. Lives in `production` because the data does; the path is under `/api/factories` for the client's convenience. |
| `service.TimelineService` | Filtered effective-event reads with enrichment. |
| `service.TimelineSummaryService` | Bucketed counts by SQL aggregation. |
| `service.FactoryStateReconstructor` | Replays effective events up to an instant → `FactoryState`. |
| `service.FactoryState`, `BatchState`, `OrderState`, `StageCounts` | Immutable records. Also consumed by `scenario` for snapshot assembly (spec 06). |
| `service.CanonicalHasher` (in `common`) | Canonical JSON + SHA-256, shared with spec 06. |
| `service.FactoryOverviewService` | Composes the overview response from current state and simple aggregates. |

Frontend: `src/features/timeline` (spec 10 owns the UI design; this spec defines its data contract).

## Reconstruction algorithm

```
reconstruct(factoryId, at):
  stages  = active workflow stages ordered by sequence                    (WorkflowReader)
  orders  = orders with placed_at <= at, ordered by (placed_at, id)
  events  = raw events of the factory with occurred_at <= at  PLUS  every CORRECTION whose target has
            original occurred_at <= at or whose replacement occurredAt <= at   (corrections are applied
            regardless of when they were recorded)
  effective = EffectiveEvents.of(events) filtered to occurredAt <= at
  for each batch (ordered by order then batch_number):
      state = BatchProjector.project(effective events of the batch)      (spec 04, same code as projections)
  orderState = derive from batch states (same rule as spec 04)
  unavailable = resource_availability windows with starts_at <= at < ends_at        (ResourceReader)
  return FactoryState(at, orders, batches, stageCounts, unavailable)
```

`BatchState`: `batchId`, `orderId`, `batchNumber`, `status` (`QUEUED`, `IN_PROGRESS`, `COMPLETED`),
`stageId`, `stageSequence`, `inputStones`, `inputCarats` (quantities entering the current stage),
`queuedAt`, `startedAt` (null unless in progress), `completedAt` (null unless completed),
`initialStones`, `initialCarats`.

Batches of orders placed at or before `at` that have no event yet at `at` cannot exist: order creation writes
`STAGE_QUEUED` at stage 1 at `placedAt` (spec 04, requirement 1.1).

Reusing `BatchProjector` is what makes requirement 4.1 hold by construction.

### Performance

One query loads the factory's events up to `at` ordered by `(batch_id, occurred_at, seq)` using the
`(factory_id, occurred_at, seq)` index; grouping is in memory. Demo scale is a few thousand events. If history
grows, add periodic state checkpoints (a `factory_state_checkpoint` table keyed by instant) and replay from
the nearest one; the reconstructor's signature does not change.

## Canonical hash

`CanonicalHasher.hash(Object)`:

1. Serialise with a dedicated `ObjectMapper`: properties sorted alphabetically, `BigDecimal` written as plain
   strings with trailing zeros stripped, `Instant` as epoch seconds, nulls included, no indentation.
2. Collections are sorted before serialisation by the producer (batches by id, orders by id).
3. SHA-256 over the UTF-8 bytes, lower-case hex.

`baseStateHash = hash(FactoryState without the 'at' echo fields that are not state)`; concretely the hash
covers `at`, `orders[]`, `batches[]`, `unavailable[]`.

## API

`GET /api/timeline?factoryId=&from=&to=&stageId=&orderId=&type=&order=asc&includeVoided=false&page=0&size=100`

```json
{ "items": [
    { "id": "…", "seq": 412, "eventType": "STAGE_COMPLETED", "occurredAt": "2026-01-12T07:40:00Z",
      "orderId": "…", "orderNumber": "SDW-2026-0004", "batchId": "…", "batchNumber": 1,
      "stageId": "…", "stageCode": "CUTTING", "stageName": "Cutting",
      "stoneQuantity": "238.0000", "caratQuantity": "301.2500", "durationSeconds": 9000,
      "payload": {}, "corrected": false, "original": null, "source": "ACTUAL" } ],
  "page": 0, "size": 100, "totalItems": 1840, "totalPages": 19 }
```

`GET /api/timeline/summary?factoryId=&bucket=DAY`

```json
{ "firstEventAt": "2026-01-05T03:30:00Z", "lastEventAt": "2026-01-19T11:55:00Z", "timeZone": "Asia/Kolkata",
  "buckets": [ { "start": "2026-01-05T18:30:00Z", "total": 96,
                 "byType": { "STAGE_STARTED": 30, "STAGE_COMPLETED": 28, "STAGE_QUEUED": 34, "ORDER_CREATED": 4 } } ] }
```

Buckets are computed with `date_trunc(:bucket, occurred_at at time zone :zone)`. Corrections are not counted
as their own bucket entries; corrected events are bucketed by effective time.

`GET /api/factories/{id}/state?at=2026-01-12T08:00:00Z`

```json
{ "factoryId": "…", "at": "2026-01-12T08:00:00Z", "baseStateHash": "9f2c…", "source": "ACTUAL",
  "orders": { "PENDING": 2, "IN_PROGRESS": 5, "COMPLETED": 3, "DISPATCHED": 1 },
  "stages": [ { "stageId": "…", "code": "POLISHING", "sequence": 6, "queued": 4, "inProgress": 3, "completedThrough": 5 } ],
  "batches": [ { "batchId": "…", "orderId": "…", "orderNumber": "SDW-2026-0004", "batchNumber": 1,
                 "status": "IN_PROGRESS", "stageId": "…", "stageCode": "POLISHING",
                 "inputStones": "236.0000", "inputCarats": "270.1000",
                 "queuedAt": "…", "startedAt": "…", "completedAt": null } ],
  "unavailable": [ { "resourceId": "…", "resourceName": "Polishing wheels", "capacityDelta": -2, "endsAt": "…" } ] }
```

`GET /api/factories/{id}/overview` → `{ orders: {active, pending, completed, delayed}, stages: [...as above],
resources: [{resourceId, name, kind, capacity, unitsInUse}], dailyOutput: [{date, stonesCompleted,
caratsCompleted}], recentEvents: [...10 timeline items], asOf, source: "ACTUAL" }`. `dailyOutput` sums output
quantities of last-stage `STAGE_COMPLETED` events per local day.

## Error cases

| Case | Status |
|---|---|
| `from` after `to`, unknown `bucket`, malformed instant | 400 |
| Unknown factory, stage or order id in a filter | 404 for the factory, 400 for filters |
| `at` before first event | 200 with empty state |

## Testing

- `FactoryStateReconstructorTest`: hand-built event lists: queued, in-progress, completed, multi-batch order, a correction recorded after `at` affecting an event before `at`, a voided event, `at` before any event.
- jqwik `ReconstructionProperties`: for generated valid histories, (a) reconstruction after the last event equals stored-projection logic; (b) stage sequence per batch is monotone non-decreasing in `at`; (c) same input gives the same `baseStateHash`; (d) reconstruction at `at` ignores any event appended later with `occurredAt > at`.
- `CanonicalHasherTest`: key order, decimal normalisation (`1.50` and `1.5` hash equal), list order sensitivity.
- `TimelineApiIT`: filters, ordering, corrected and voided handling, pagination, summary buckets across a time-zone day boundary.
- `FactoryStateIT`: reconstruct at seeded instants; assert no row count changes; overview totals equal direct SQL counts.
