---
inclusion: always
---

# RealityCheck — Architecture

A modular monolith: one Spring Boot application, one PostgreSQL database, one React single-page application.
Modules are Java packages with enforced dependency rules, not separate deployables.

## Backend modules

Base package `com.realitycheck`.

| Package | Owns | May depend on |
|---|---|---|
| `common` | Error handling, API envelope types, CORS and OpenAPI config, clock, system info | nothing |
| `factory` | Factory, working calendar (shifts, breaks, closed days), `FactoryResolver`, `FactoryCountProvider` port | `common` |
| `workflow` | Workflow, WorkflowStage, StageRequirement | `factory`, `resource` (ids only) |
| `resource` | Resource pools, ResourceAvailability (downtime, capacity overrides) | `factory` |
| `production` | ProductionOrder, ProductionBatch, OperationalEvent, timeline, state reconstruction | `factory`, `workflow`, `resource` (ids and ports) |
| `scenario` | Scenario, ScenarioChange, snapshot assembly, comparison | `factory`, `workflow`, `resource`, `production` |
| `simulation.engine` | The discrete-event engine. **Pure Java.** | JDK only |
| `simulation` | SimulationRun and results persistence, async execution, engine adapter | `scenario`, `simulation.engine` |
| `ai` | Provider abstraction, prompt assembly, response validation, insights | `simulation`, `scenario` (read-only results and comparisons) |
| `demo` | Seed and reset of the demonstration factory | all feature modules |

Rules enforced by ArchUnit:

1. `simulation.engine` imports nothing from Spring, Jakarta Persistence, Jackson, or any other
   `com.realitycheck` package.
2. Nothing outside `ai` imports an LLM client. `simulation` and `simulation.engine` never import `ai`.
3. Controllers do not use repositories; entities do not appear in controller signatures.

Inside a feature package: `api` (controllers and DTOs), `domain` (entities, enums), `service`,
`persistence` (repositories). A feature's DTO records live together in one `<Feature>Dtos` holder class.

Modules exchange records, never entities, and refer to each other's rows by id. Where a lower module needs
something from a higher one, it declares an interface that the higher module implements
(`FactoryCountProvider`, `RequirementUsage`). Demonstration seed sections live in `demo` and create rows
through the feature services, so seeded data passes the same validation as API input.

## Data model

All tables have a UUID primary key, `created_at timestamptz`, and (where editable) `updated_at` plus a
`version` column for optimistic locking. Every aggregate root carries `factory_id`.

**Factory:** `factory` (name, time zone, currency), `working_shift` (day of week, start, end),
`calendar_break`.
**Workflow:** `workflow`, `workflow_stage` (sequence, rate, setup, stone yield, carat yield, fixed cost),
`stage_requirement` (stage, resource, units per slot).
**Resource:** `resource` (kind MACHINE or EMPLOYEE, category, capacity, hourly cost, status),
`resource_availability` (time window, capacity delta, reason).
**Production:** `production_order`, `production_batch`, `operational_event` (append-only).
**Scenario:** `scenario` (parent, fork timestamp), `scenario_change` (type, JSONB payload, ordinal).
**Simulation:** `simulation_run` (status, input snapshot JSONB, input hash, algorithm version, random seed,
summary JSONB), `simulation_stage_result`, `simulation_resource_result`, `simulation_event_result`.
**AI:** `simulation_insight`, `insight_evidence`.

### Modelling decisions

- **Order versus batch.** A production order is the customer commitment (number, priority, due date). A batch
  is the physical parcel that moves through stages (stone count, carat weight, current stage). One order has
  one or more batches. The simulation's unit of work is the batch.
- **Resources are pools.** A resource row is a group of interchangeable units ("Polishing wheels", capacity 6;
  "Polishers", capacity 14). "Five employees unavailable" is a capacity change of -5 on a pool. Per-person
  rosters are a non-goal.
- **Stones and carats are separate quantities** with separate yields. Rates are expressed in stones.
- **Operational events are append-only.** The table rejects `UPDATE` and `DELETE` with a database trigger.
  Corrections are new events of type `CORRECTION` that reference the corrected event.
- **Configuration is captured, not reconstructed.** Work-in-progress at a fork timestamp is rebuilt by
  replaying events. Resource and stage configuration is taken as it is when the snapshot is captured, with
  `resource_availability` windows applied by date. Editing a resource later never changes an existing run
  because each run stores its full input snapshot.

## Scenario and simulation flow

```
POST /api/scenarios/{id}/simulate
  1. validate scenario and its changes
  2. reconstruct factory state at fork timestamp (replay operational_event <= fork)
  3. assemble input snapshot: calendar, stages, requirements, resources, WIP, pending orders
  4. apply scenario changes to the snapshot (never to live tables)
  5. hash the canonical snapshot + algorithm version -> input_hash
  6. insert simulation_run PENDING with the snapshot; return 202 + runId
  7. background executor: RUNNING -> engine.run(snapshot) -> results
  8. one transaction: write stage/resource/event results + summary, set COMPLETED
     on any failure: roll back results, set FAILED with a safe message
```

- **Idempotency.** The client sends `Idempotency-Key`; `(scenario_id, idempotency_key)` is unique. A repeated
  key returns the original run. A run whose `input_hash` matches a COMPLETED run for the same scenario is
  returned instead of re-executed unless `force=true`.
- **Snapshot immutability.** `input_snapshot` is written once at PENDING and never updated.
- **Crash recovery.** On startup, runs left in PENDING or RUNNING are marked FAILED ("interrupted").
- **Executor.** A bounded `ThreadPoolTaskExecutor`; a full queue returns HTTP 429.
- **Comparison.** Computed on demand from stored results; there is no comparison table. Two runs are comparable by default only if their fork timestamp and base-state hash match.
  Comparing across different starting states requires an explicit `allowDifferentBaseline=true`.

### Scenario change types

`RESOURCE_CAPACITY` (absolute or delta, optional percent), `RESOURCE_UNAVAILABLE` (units, optional window),
`RESOURCE_DOWNTIME` (window), `DEMAND_VOLUME` (percent on pending orders), `PROCESSING_RATE` (stage, percent
or absolute), `WORKING_SCHEDULE` (add or replace shifts). Payloads are JSONB validated per type by a typed
record before persistence.

## API conventions

- Base path `/api`. JSON only. Resource names are plural nouns.
- Collections are factory-scoped with `?factoryId=`; when omitted and exactly one factory exists, it is used.
- Lists return `{ "items": [...], "page": 0, "size": 50, "totalItems": 123, "totalPages": 3 }`.
  `page` is zero-based; `size` is capped at 200.
- Single resources return the object directly. Creation returns 201 with a `Location` header. Simulation
  submission returns 202.
- Errors are `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, and for
  validation failures an `errors` array of `{ field, message }`.
- Optimistic-lock conflicts return 409. Unknown ids return 404. Invalid state transitions return 422.
- Timestamps are ISO-8601 UTC strings. Durations are integer seconds with the unit in the field name.
- Every numeric result field that the UI displays is accompanied, at the object level, by
  `source: "ACTUAL" | "SIMULATED"`.

Endpoints beyond the original list, added because the flows need them: `PATCH /api/scenarios/{id}`,
`PUT` and `DELETE /api/scenarios/{id}/changes/{changeId}`, `GET /api/scenarios/{id}/simulations`,
`GET /api/factories/{id}/state?at=`, `GET /api/resources/{id}`, `POST /api/resources/{id}/availability`,
`GET /api/system/info`, `POST /api/demo/reset` (only when
`realitycheck.demo.reset-enabled=true`).

## Frontend modules

```
src/app/         Router, providers, layout shell (sidebar, header)
src/components/  ui/ (shadcn primitives), shared building blocks (PageHeader, StatusBadge, SourceTag)
src/features/    overview, factory, workflow, resources, orders, timeline, scenarios, results, analyst, settings
src/lib/         api client, zod schemas, formatters (durations, money, quantities), query keys
```

A feature folder owns its routes, queries, schemas and components. Features import from `lib` and
`components`, never from each other; shared things move down.

## AI boundary

`AiProvider` is the single interface (`complete(AiRequest) -> AiResponse`). Implementations: an LLM-backed
provider selected by `AI_PROVIDER`, and a deterministic template provider used when no key is configured or
the LLM fails. The `ai` module builds a structured evidence document from persisted simulation results, asks
for structured JSON, validates it against a schema, and rejects any response containing a number that does
not appear in the evidence. Insights are stored with their evidence rows. The application is fully usable
with AI disabled.

## Deployment

Docker Compose runs `db`, `backend` and `frontend`. Flyway migrates on backend start. The demo seed runs on
start when `REALITYCHECK_DEMO_SEED=true` and the database has no factory. Host ports are configurable in
`.env`; the database defaults to host port 15432 to avoid clashing with a locally installed PostgreSQL.
