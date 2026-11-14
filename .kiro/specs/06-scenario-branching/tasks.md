# 06 Scenario Branching — Tasks

Milestone: **M5 — Scenario branching**. Depends on specs 01-05. Spec 07's engine (M4) defines the limits and
bottleneck configuration that the snapshot carries.

- [x] 1. Add the scenario schema
  - Flyway migration for `scenario` and `scenario_change` (deferred unique ordinal).
  - Entities, `ChangeType`, repositories.
  - Test: `ScenarioRepositoryIT` for unique name, cascade delete of changes, deferred ordinal swap in one transaction.
  - _Requirements: 1.1, 3.1, 3.5_

- [x] 2. Implement typed change payloads
  - Sealed `ChangePayload` with six records, JSON conversion and field validation.
  - Test: `ChangePayloadsTest`.
  - _Requirements: 3.1, 3.2, 4.8_

- [x] 3. Implement scenario CRUD
  - Create (with `forkAt` range check), list with latest-run summary (null until spec 07), get, patch with optimistic locking, `forkAt` lock once a run exists.
  - Test: `ScenarioApiIT` CRUD and conflict cases.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

- [x] 4. Implement change endpoints
  - `POST`, `PUT`, `DELETE` with reference checks, ordinal maintenance, version bump, generated `summary`.
  - Test: `ScenarioApiIT` for add, edit, delete and renumber, foreign-resource rejection.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.7_

- [x] 5. Implement branching
  - Copy-on-create of parent changes with `inherited=true`.
  - Test: child has the parent's changes; editing the parent afterwards leaves the child unchanged.
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [x] 6. Define the snapshot records and `SnapshotAssembler`
  - Records in `scenario.snapshot`; assembly from `FactoryCalendarService`, `WorkflowReader`, `ResourceReader`, `FactoryStateReconstructor`; stable sorting; future orders.
  - Test: `SnapshotAssemblerIT`.
  - _Requirements: 6.1, 6.4, 6.5_

- [x] 7. Implement `ChangeApplier`
  - Pure application of each change type in ordinal order; fills `appliedChanges`.
  - Test: `ChangeApplierTest`; jqwik `ChangeApplierProperties`.
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_

- [x] 8. Implement `SnapshotValidator` and hashing
  - Errors (unsatisfiable requirement, empty calendar with work, non-positive rate) and `NO_EFFECT` warnings; `ScenarioSnapshotService.assemble` returning both hashes.
  - Test: unit tests per error and warning; equal `baseStateHash` for equal forks; `inputHash` changes when any change or the algorithm version changes.
  - _Requirements: 5.2, 5.3, 6.2, 6.3_

- [x] 9. Expose the validate endpoint
  - `POST /api/scenarios/{id}/validate` with the before/after preview.
  - Test: `ScenarioApiIT` asserts the preview values for each demo scenario and that no rows are written.
  - _Requirements: 5.1, 5.4_

- [x] 10. Prove isolation from live data
  - Test: `ScenarioIsolationIT`; plus a test that editing a scenario's changes leaves an existing run's `input_snapshot` untouched (after spec 07 task 12).
  - _Requirements: 3.6, 4.7_

- [x] 11. Seed the six demonstration scenarios
  - `ScenarioSeedSection`; fork timestamp = seed "now" minus five working days; no runs pre-populated.
  - Test: all six validate with `valid: true`; the baseline has zero changes.
  - _Requirements: 7.1, 7.2_

- [x] 12. Document the endpoints
  - OpenAPI annotations; Scenarios section in `docs/api.md` with one example payload per change type and the rounding rules.
  - _Requirements: 4.1, 4.4_

## Implementation notes (Milestone 5)

All twelve tasks are built. Differences from design.md:

- **Packages.** Change payloads, `ChangeApplier` and `ChangeSummaries` live in `scenario.change`; snapshot
  records, `SnapshotJson` (canonical JSON and SHA-256) and the `SimulationEngineContract` port live in
  `scenario.snapshot`; `SnapshotAssembler`, `ScenarioSnapshotService`, `ScenarioService` and `ScenarioReader`
  in `scenario.service`. There is no separate `SnapshotValidator`: structural problems are found by applying
  the changes, no-effect warnings are produced by `ChangeApplier` while applying, and the engine's own
  `InputValidator` is run on the snapshot through `SimulationEngineContract` (implemented in `simulation`),
  so the validation rules exist once.
- **Change validation is whole-list.** Every add, edit or delete re-applies the scenario's complete change
  list to the factory's configuration at the fork. An edit that would make a later change impossible is
  rejected with 400 naming `changes[n].payload.<field>`.
- **`includeFutureOrders`** is part of the starting state: changing it is locked once a run exists, like `forkAt`.
- **Downtime** subtracts the base capacity plus any units added by windows in the period, so the pool is
  certainly at zero; the engine floors at zero.
- **Orders** carry `placedAt`; no `completedBatchCount` (see spec 07 implementation notes).
- **Schedule changes** reject shifts that overlap the existing calendar (ADD) at creation.
- **Warnings** besides `NO_EFFECT`: `EXCEEDS_CAPACITY` when more units are made unavailable than the pool has.
- **Demo scenarios** are created one second apart, ending at the seeding instant, so lists show them in the
  order of requirement 7.1.
- **Tests:** `ChangePayloadsTest`, `ChangeApplierTest`, `ChangeApplierProperties`, `SnapshotJsonTest`,
  `ScenarioApiIT` (CRUD, conflicts, ordinal renumbering, branching, validation preview, isolation from live
  tables by row checksum), `DemoScenariosIT` (the six seeded scenarios). `ScenarioRepositoryIT` was not
  written: the deferred ordinal constraint is exercised by deleting a change in `ScenarioApiIT`, and the
  unique name by the 409 case.
