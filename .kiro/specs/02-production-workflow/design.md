# 02 Production Workflow — Design

## Components

Package `com.realitycheck.workflow`:

| Class | Responsibility |
|---|---|
| `api.WorkflowController` | `GET /api/workflows`, `GET /api/workflows/{id}`, `POST /api/workflows`, `GET /api/workflows/{id}/stages`. |
| `api.CreateWorkflowRequest`, `CreateStageRequest`, `StageRequirementRequest`, `WorkflowResponse`, `StageResponse`, `StageRequirementResponse` | DTO records. |
| `service.WorkflowService` | Creation with sequence, code and requirement validation; single-active rule; reads. |
| `service.WorkflowReader` | Read-only view for other modules: active workflow of a factory with stages and requirements as immutable records (`WorkflowView`, `StageView`, `RequirementView`). |
| `domain.Workflow`, `WorkflowStage`, `StageRequirement`, `WorkflowStatus` | Entities and enum (`DRAFT`, `ACTIVE`, `ARCHIVED`). |
| `persistence.*Repository` | Repositories. |
| `demo.WorkflowSeedSection` (order 30, after resources) | Seeds the eight stages. |

`workflow` references resource pools by id only. To validate requirement targets and to show pool
name/kind/capacity it calls `resource.service.ResourceReader` (spec 03), which returns records, not entities.

## Data model

```sql
workflow (
  id          uuid primary key,
  factory_id  uuid not null references factory(id),
  name        varchar(120) not null,
  description text,
  status      varchar(16) not null check (status in ('DRAFT','ACTIVE','ARCHIVED')),
  version     bigint not null default 0,
  created_at  timestamptz not null,
  updated_at  timestamptz not null,
  unique (factory_id, name)
)
create unique index workflow_one_active_per_factory on workflow(factory_id) where status = 'ACTIVE';

workflow_stage (
  id                 uuid primary key,
  workflow_id        uuid not null references workflow(id) on delete cascade,
  code               varchar(40)  not null,
  name               varchar(120) not null,
  description        text,
  sequence           integer not null check (sequence >= 1),
  stones_per_hour    numeric(12,4) not null check (stones_per_hour > 0),      -- per processing slot
  setup_seconds      bigint  not null check (setup_seconds >= 0),             -- per job
  stone_yield        numeric(7,6) not null check (stone_yield > 0 and stone_yield <= 1),
  carat_yield        numeric(7,6) not null check (carat_yield > 0 and carat_yield <= 1),
  fixed_cost_per_job numeric(14,2) not null default 0 check (fixed_cost_per_job >= 0),
  version            bigint not null default 0,
  created_at         timestamptz not null,
  updated_at         timestamptz not null,
  unique (workflow_id, sequence),
  unique (workflow_id, code)
)

stage_requirement (
  id             uuid primary key,
  stage_id       uuid not null references workflow_stage(id) on delete cascade,
  resource_id    uuid not null references resource(id),          -- table owned by spec 03
  units_per_slot integer not null check (units_per_slot >= 1),
  created_at     timestamptz not null,
  unique (stage_id, resource_id)
)
```

Indexes: `workflow(factory_id)`, `workflow_stage(workflow_id, sequence)` (from the unique constraint),
`stage_requirement(resource_id)`.

The `resource` table must exist first, so the migration for this spec is numbered after spec 03's.

## API

`POST /api/workflows`

```json
{
  "factoryId": "…",
  "name": "Diamond Manufacturing",
  "status": "ACTIVE",
  "stages": [
    { "code": "POLISHING", "name": "Polishing", "description": "…", "sequence": 6,
      "stonesPerHour": "12.0000", "setupSeconds": 900,
      "stoneYield": "0.990000", "caratYield": "0.920000", "fixedCostPerJob": "150.00",
      "requirements": [ { "resourceId": "…", "unitsPerSlot": 1 }, { "resourceId": "…", "unitsPerSlot": 2 } ] }
  ]
}
```

`GET /api/workflows/{id}/stages` → 200

```json
{
  "items": [
    { "id": "…", "code": "POLISHING", "name": "Polishing", "sequence": 6,
      "stonesPerHour": "12.0000", "setupSeconds": 900,
      "stoneYield": "0.990000", "caratYield": "0.920000", "fixedCostPerJob": "150.00",
      "requirements": [
        { "resourceId": "…", "resourceName": "Polishing wheels", "kind": "MACHINE", "unitsPerSlot": 1, "poolCapacity": 6 },
        { "resourceId": "…", "resourceName": "Polishers", "kind": "EMPLOYEE", "unitsPerSlot": 2, "poolCapacity": 14 } ],
      "maxSlots": 6 }
  ],
  "page": 0, "size": 50, "totalItems": 8, "totalPages": 1
}
```

Decimals are serialised as strings to avoid precision loss in JavaScript.

Live figures the Workflow Explorer also shows (queue length, in-progress count, stage status) are not part of
this resource. They come from `GET /api/factories/{id}/state` (spec 05). Simulated utilization and average
duration come from a simulation run (spec 07).

## Validation rules

1. `stages` non-empty; sequences sorted are exactly `1..N`.
2. Codes match `^[A-Z][A-Z0-9_]{1,39}$` and are unique within the workflow.
3. Each requirement's pool exists, belongs to the same factory and is `ACTIVE`.
4. No duplicate pool within a stage.
5. Single ACTIVE workflow per factory: checked in the service for a clean 409; the partial unique index is the
   safety net for races.

## Extensibility

The engine consumes stages as an ordered list. Parallel branches would add a `stage_transition` table
(from-stage, to-stage) and replace "next sequence" with a successor lookup. Nothing in this design stores
derived data that assumes linearity, other than `sequence`.

## Error cases

| Case | Status |
|---|---|
| Invalid sequence, duplicate code, bad parameter range, bad or foreign pool, duplicate pool | 400 |
| Second ACTIVE workflow, duplicate workflow name | 409 |
| Unknown workflow | 404 |

## Testing

- `WorkflowServiceTest`: every validation rule; `maxSlots` derivation (including no requirements → `null`, and `floor`).
- `WorkflowApiIT`: create and read back; database `CHECK` constraints reject out-of-range values inserted directly; one-active index.
- `WorkflowSeedIT`: eight stages, expected codes and order, every requirement resolves to a seeded pool.
