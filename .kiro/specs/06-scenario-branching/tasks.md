# 06 Scenario Branching — Tasks

Milestone: **M5 — Scenario branching**. Depends on specs 01-05. Spec 07's engine (M4) defines the limits and
bottleneck configuration that the snapshot carries.

- [ ] 1. Add the scenario schema
  - Flyway migration for `scenario` and `scenario_change` (deferred unique ordinal).
  - Entities, `ChangeType`, repositories.
  - Test: `ScenarioRepositoryIT` for unique name, cascade delete of changes, deferred ordinal swap in one transaction.
  - _Requirements: 1.1, 3.1, 3.5_

- [ ] 2. Implement typed change payloads
  - Sealed `ChangePayload` with six records, JSON conversion and field validation.
  - Test: `ChangePayloadsTest`.
  - _Requirements: 3.1, 3.2, 4.8_

- [ ] 3. Implement scenario CRUD
  - Create (with `forkAt` range check), list with latest-run summary (null until spec 07), get, patch with optimistic locking, `forkAt` lock once a run exists.
  - Test: `ScenarioApiIT` CRUD and conflict cases.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

- [ ] 4. Implement change endpoints
  - `POST`, `PUT`, `DELETE` with reference checks, ordinal maintenance, version bump, generated `summary`.
  - Test: `ScenarioApiIT` for add, edit, delete and renumber, foreign-resource rejection.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.7_

- [ ] 5. Implement branching
  - Copy-on-create of parent changes with `inherited=true`.
  - Test: child has the parent's changes; editing the parent afterwards leaves the child unchanged.
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [ ] 6. Define the snapshot records and `SnapshotAssembler`
  - Records in `scenario.snapshot`; assembly from `FactoryCalendarService`, `WorkflowReader`, `ResourceReader`, `FactoryStateReconstructor`; stable sorting; future orders.
  - Test: `SnapshotAssemblerIT`.
  - _Requirements: 6.1, 6.4, 6.5_

- [ ] 7. Implement `ChangeApplier`
  - Pure application of each change type in ordinal order; fills `appliedChanges`.
  - Test: `ChangeApplierTest`; jqwik `ChangeApplierProperties`.
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_

- [ ] 8. Implement `SnapshotValidator` and hashing
  - Errors (unsatisfiable requirement, empty calendar with work, non-positive rate) and `NO_EFFECT` warnings; `ScenarioSnapshotService.assemble` returning both hashes.
  - Test: unit tests per error and warning; equal `baseStateHash` for equal forks; `inputHash` changes when any change or the algorithm version changes.
  - _Requirements: 5.2, 5.3, 6.2, 6.3_

- [ ] 9. Expose the validate endpoint
  - `POST /api/scenarios/{id}/validate` with the before/after preview.
  - Test: `ScenarioApiIT` asserts the preview values for each demo scenario and that no rows are written.
  - _Requirements: 5.1, 5.4_

- [ ] 10. Prove isolation from live data
  - Test: `ScenarioIsolationIT`; plus a test that editing a scenario's changes leaves an existing run's `input_snapshot` untouched (after spec 07 task 12).
  - _Requirements: 3.6, 4.7_

- [ ] 11. Seed the six demonstration scenarios
  - `ScenarioSeedSection`; fork timestamp = seed "now" minus five working days; no runs pre-populated.
  - Test: all six validate with `valid: true`; the baseline has zero changes.
  - _Requirements: 7.1, 7.2_

- [ ] 12. Document the endpoints
  - OpenAPI annotations; Scenarios section in `docs/api.md` with one example payload per change type and the rounding rules.
  - _Requirements: 4.1, 4.4_
