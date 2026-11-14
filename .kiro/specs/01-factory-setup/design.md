# 01 Factory Setup — Design

## Components

Package `com.realitycheck.factory`:

| Class | Responsibility |
|---|---|
| `api.FactoryController` | `GET /api/factories`, `GET /api/factories/{id}`, `POST /api/factories`. Thin. |
| `api.CreateFactoryRequest`, `FactoryResponse`, `FactorySummaryResponse`, `CalendarRequest`, `CalendarResponse`, `ShiftRequest`, `ShiftResponse`, `BreakRequest`, `BreakResponse` | DTO records with Bean Validation annotations. |
| `service.FactoryService` | Create, list, get. Cross-field calendar validation. |
| `service.FactoryCalendarService` | Reads a factory's shifts and breaks as an immutable `FactoryCalendar` value for other modules (scenario snapshot assembly). |
| `domain.Factory`, `WorkingShift`, `CalendarBreak` | JPA entities. |
| `persistence.FactoryRepository`, `WorkingShiftRepository` | Spring Data repositories. |

Package `com.realitycheck.demo`:

| Class | Responsibility |
|---|---|
| `DemoSeeder` | Orchestrates ordered `DemoSeedSection` beans inside one transaction. |
| `DemoSeedSection` | Interface: `int order()`, `void seed(DemoSeedContext ctx)`. Each feature spec contributes one implementation. |
| `DemoSeedContext` | Holds the anchor instant, a deterministic id function `id(String key)` (`UUID.nameUUIDFromBytes("realitycheck-demo:" + key)`), and ids created by earlier sections. |
| `DemoStartupRunner` | `ApplicationRunner`; seeds when enabled and no factory exists. |
| `DemoResetService`, `DemoController` | Reset endpoint, registered only when `realitycheck.demo.reset-enabled=true` (`@ConditionalOnProperty`). |
| `FactorySeedSection` (order 10) | Creates the factory and calendar. |

Counts in `FactoryResponse` come from small read interfaces (`WorkflowCounts`, `ResourceCounts`,
`OrderCounts`) declared in `factory` and implemented in the owning modules, so `factory` keeps depending only
on `common`.

## Data model

```sql
factory (
  id            uuid primary key,
  name          varchar(120) not null unique,
  description   text,
  time_zone     varchar(64)  not null,
  currency_code char(3)      not null,
  demo          boolean      not null default false,
  version       bigint       not null default 0,
  created_at    timestamptz  not null,
  updated_at    timestamptz  not null
)

working_shift (
  id          uuid primary key,
  factory_id  uuid not null references factory(id) on delete cascade,
  day_of_week smallint not null check (day_of_week between 1 and 7),   -- ISO: 1 = Monday
  start_time  time not null,
  end_time    time not null,
  created_at  timestamptz not null,
  check (end_time > start_time),
  unique (factory_id, day_of_week, start_time)
)

calendar_break (
  id               uuid primary key,
  working_shift_id uuid not null references working_shift(id) on delete cascade,
  start_time       time not null,
  end_time         time not null,
  created_at       timestamptz not null,
  check (end_time > start_time)
)
```

Indexes: `working_shift(factory_id)`, `calendar_break(working_shift_id)`. Overlap between shifts and
break-inside-shift are checked in `FactoryService` (they need more than one row).

## API

`POST /api/factories`

```json
{
  "name": "Surat Diamond Works",
  "description": "Fictional demonstration factory",
  "timeZone": "Asia/Kolkata",
  "currencyCode": "INR",
  "calendar": {
    "shifts": [
      { "dayOfWeek": 1, "startTime": "09:00", "endTime": "18:00",
        "breaks": [ { "startTime": "13:00", "endTime": "14:00" } ] }
    ]
  }
}
```

`GET /api/factories/{id}` → 200

```json
{
  "id": "…", "name": "Surat Diamond Works", "description": "…",
  "timeZone": "Asia/Kolkata", "currencyCode": "INR", "demo": true,
  "calendar": { "shifts": [ { "id": "…", "dayOfWeek": 1, "startTime": "09:00", "endTime": "18:00",
                              "breaks": [ { "startTime": "13:00", "endTime": "14:00" } ] } ],
                "weeklyWorkingSeconds": 172800 },
  "counts": { "workflows": 1, "resources": 14, "orders": 12 },
  "createdAt": "2026-01-05T03:30:00Z", "updatedAt": "2026-01-05T03:30:00Z"
}
```

`GET /api/factories?page=0&size=50` → list envelope of `{ id, name, timeZone, currencyCode, demo }`.

`POST /api/demo/reset` → `{ "factoryId": "…", "seededAt": "…" }`.

## Factory resolution

`common` provides `FactoryResolver.resolve(UUID factoryIdOrNull)`: returns the given id if it exists (else
404); when null and exactly one factory exists returns it; when null and zero or several exist returns 400
with detail "factoryId is required". Every factory-scoped list endpoint in other specs uses it.

## Seed determinism

- Anchor instant: `2026-01-05T03:30:00Z` (Monday 09:00 `Asia/Kolkata`). Every seeded timestamp is the anchor
  plus a fixed offset. Nothing in the seed reads the clock.
- `created_at` and `updated_at` of seeded rows are the anchor.
- Sections run in `order()`; later sections look up earlier ids through `DemoSeedContext`.

## Reset

`DemoResetService.reset()`:

1. Refuse with 409 if any `simulation_run` is PENDING or RUNNING.
2. `TRUNCATE` all application tables in one statement with `RESTART IDENTITY CASCADE`. `TRUNCATE` does not
   fire the row-level append-only trigger on `operational_event` (spec 04), which is the intended and only
   bypass.
3. Run `DemoSeeder.seed()`.

Steps 2 and 3 share one transaction.

## Error cases

| Case | Status |
|---|---|
| Bean Validation failure, bad zone, overlapping shifts, break outside shift | 400 with `errors[]` |
| Duplicate factory name | 409 |
| Unknown factory id | 404 |
| Reset while a run is active | 409 |
| Reset disabled | 404 (endpoint not registered) |

## Testing

- `FactoryServiceTest` (Mockito): overlap detection, break containment, zone validation.
- `FactoryApiIT` (Testcontainers): create, fetch, list, duplicate name, problem+json shape.
- `DemoSeederIT`: seed into an empty database twice (with truncate between) and assert identical row sets;
  seed is skipped when a factory exists; failure in a section leaves the database empty.
- `DemoResetIT`: reset restores the initial row counts; 409 with an active run; endpoint absent when disabled.
