# Architecture

This document explains how RealityCheck is put together and why. The binding rules for day-to-day work are
in `.kiro/steering/` (`architecture.md` for module boundaries and API conventions, `simulation-rules.md` for
the engine contract). This page is the narrative: the shape of the system, the decisions that are not
obvious, and what was traded away.

Status: Milestone 1 is built. Sections describing later milestones are the agreed design, not a description
of existing code; each says so.

## System shape

```
 Browser ── React SPA ──/api──▶ Spring Boot application ──▶ PostgreSQL
            (nginx in Docker)    │
                                 ├─ feature modules (factory, workflow, resource, production, scenario)
                                 ├─ simulation  ──▶ simulation.engine  (pure Java, no framework)
                                 └─ ai  ──▶ LLM provider (optional, replaceable)
```

One deployable backend, one database, one static frontend. A modular monolith was chosen over services
because the workload is a single team's product with one consistency boundary: a simulation run must read a
coherent picture of factory, workflow, resources and history. Splitting that across services would add
distributed-transaction problems and solve none that exist.

Modules are Java packages. Their dependency rules are checked on every build by `ArchitectureTest`
(ArchUnit), including for packages that do not exist yet, so the first class added to
`simulation.engine` is already bound by them.

## The three kinds of value

Everything a user sees is one of:

| Kind | Origin | May be edited |
|---|---|---|
| **Actual** | Recorded operational events and configuration | Only by appending correcting events |
| **Simulated** | Output of the engine for a stored input snapshot | Never; re-run instead |
| **AI interpretation** | Text generated from simulated and actual values | Regenerated, never fed back |

The architecture exists to keep these apart. Dependencies point one way: `ai` reads `simulation` results;
`simulation` cannot import `ai`; the engine cannot import anything.

## What exists today (Milestone 1)

- `common.config`: typed settings (`RealityCheckProperties`), CORS allow-list, OpenAPI metadata, the
  application `Clock`.
- `common.error`: one `@RestControllerAdvice` that converts every failure to an RFC 9457 problem document.
  Only text written for clients is returned; framework and driver messages go to the log.
- `common.api`: `PageResponse`, the list envelope.
- `common.system`: `GET /api/system/info`, reporting live database and migration state.
- `V1__baseline.sql`: two trigger functions later tables attach to, `set_updated_at()` and
  `reject_modification()` (the append-only guard).
- Frontend shell: sidebar navigation for the ten screens, a live system-status view, placeholders elsewhere.
- Docker Compose with health-gated start-up order.

## Design for the milestones ahead

### History and the time machine

Operational events are append-only, enforced in the database by a trigger rather than by convention, so no
code path can rewrite history. A scenario forks at a timestamp. Work in progress at that moment is rebuilt
by replaying events up to it.

Configuration is treated differently: resources and stages are editable rows, and the design does **not**
try to reconstruct what a resource's capacity was at an arbitrary past moment. Instead each simulation run
stores the complete input it was given. This gives the property that matters (a run can always be
reproduced and never changes after the fact) without building full temporal versioning of every
configuration table. The cost is that forking far into the past uses today's configuration plus dated
availability windows. Full effective-dated configuration is the natural next step if that becomes a limit.

### Simulation runs

Submission is asynchronous: validate, build and store the input snapshot, return a run id, execute on a
bounded executor, write all results in one transaction. A failed run stores no partial results. Duplicate
submissions are absorbed by an idempotency key and by hashing the canonical input. Runs interrupted by a
restart are marked failed at start-up instead of staying "running" forever.

### The engine

A pure function from an input record to a result record, with whole-second integer time, fixed-point
decimals, and a totally ordered event queue. The complete contract is in
`.kiro/steering/simulation-rules.md`. Three modelling choices deserve a note here:

- **Resources are pools.** A row is a group of interchangeable units, not a named person or machine. This
  is the standard discrete-event formulation, keeps allocation simple and atomic, and expresses every MVP
  scenario ("five employees unavailable" is minus five on a pool). Per-person rosters are out of scope.
- **A job is one batch on one processing slot.** More slots let more batches run at once; they do not speed
  up a single batch. Elapsed time and resource-hours are therefore separate, correctly different, metrics.
- **Stones stay fractional inside a run.** Yield is applied exactly at each stage and quantities are rounded
  only for display, so nothing is lost to truncation across eight stages.

### A property that is deliberately not asserted

"Adding capacity never makes completion later" sounds self-evident and is false for non-preemptive
priority scheduling across stages that share resources: extra capacity can change the order in which jobs
start and lengthen the overall schedule. The tests assert the property where it provably holds (a single
stage with a fixed job order) and report it as a statistic elsewhere. If a scenario shows a worse outcome
after adding capacity, that is the dispatch rule's real consequence and the product shows it.

### Cost

Employees are costed for the hours they are scheduled, machines for the hours they run. Costing only busy
time would make adding idle staff look free. Because scenarios finish at different times, results also carry
cost per output stone so that comparisons are not distorted by horizon length.

### AI

One interface, `AiProvider`. The `ai` module assembles an evidence document from stored results, requests
structured JSON, validates it, and rejects any response containing a number absent from the evidence. A
deterministic template provider serves when no key is configured or the model call fails, so the
application is complete without AI.

## Decisions that differ from the original brief

| Brief | Decision | Reason |
|---|---|---|
| Spring Security in the stack | Deferred until authentication exists | Adding it without users only produces a locked-down default to be disabled again. CORS, actuator exposure and error output are already restricted. |
| User and Workspace entities | Deferred; `factory_id` kept on every aggregate | No multi-user flow in the MVP; the key needed to add tenancy later is in place. |
| React Flow for the workflow view | Not used | The workflow is a fixed sequence of eight stages; a custom layout is simpler and lighter. |
| Individual employees | Pools with capacity | See above. |
| Single "expected yield" per stage | Separate stone yield and carat yield | Cutting and polishing lose weight, not stones; one number cannot express both. |
| Processing time divided by parallel slots | One slot per job | Avoids conflating elapsed time with resource-hours; batch splitting is a later extension. |
| Reconstruct full factory state from history | Replay events for work in progress; snapshot configuration per run | Reproducibility without temporal versioning of every table. |
| Seed data | Application seeder with a guarded reset endpoint, not a Flyway migration | Migrations are permanent; demo data must be resettable. |

Added because the flows need them: a factory time zone and currency, calendar tables, a batch reference on
events, a `CORRECTION` event type, per-resource result rows, an input hash and idempotency key on runs, and
the extra endpoints listed in `docs/api.md`.

## Known risks

- **Event-result volume.** One run can emit tens of thousands of event rows. Inserts are batched; the
  results endpoint is paginated. If volume grows, event results become opt-in per run.
- **Long forks.** Replaying a long history for each submission is linear in events up to the fork. Periodic
  state checkpoints are the remedy if it shows up in measurements.
- **Heuristic bottleneck detection.** Weights and thresholds are configuration and every signal is shown, so
  a questionable classification can be inspected rather than trusted.
