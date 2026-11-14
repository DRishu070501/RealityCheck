# 08 Scenario Comparison — Tasks

Milestone: **M5 — Scenario branching** (tasks 1-7). Task 8 is **M8**. Depends on spec 07 Part B.

- [ ] 1. Implement `DeltaCalculator`
  - Absolute, percent and direction functions.
  - Test: `DeltaCalculatorTest` and the jqwik antisymmetry property.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [ ] 2. Define `RunResultsPort` and implement it in `simulation`
  - `RunView` record (metadata, summary, stage, resource and order results, applied changes from the snapshot); `SimulationQueryService` implements the port.
  - Test: integration test that `RunView` for a completed run matches the run's API responses.
  - _Requirements: 1.1, 6.1_

- [ ] 3. Implement `MetricCatalog` and summary comparison
  - Catalog entries with extractor, unit and `betterWhen`; build `MetricDelta` list per alternative.
  - Test: every catalog key resolves on a real `RunView`; self-comparison is all zero and `UNCHANGED`.
  - _Requirements: 2.1, 2.4, 2.5, 2.6_

- [ ] 4. Implement comparability checks and warnings
  - The check in design.md with the three warning codes.
  - Test: `ComparisonServiceTest` for each branch.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

- [ ] 5. Implement stage, resource, order and bottleneck comparison
  - Join by stage id, pool id and order id with one-sided nulls; bottleneck change table.
  - Test: `ComparisonServiceTest` for one-sided entries, each `change` value, unfinished orders.
  - _Requirements: 3.1, 3.2, 3.3, 4.1, 4.2, 5.1, 5.2_

- [ ] 6. Expose `POST /api/simulations/compare`
  - Request validation, response assembly, problem+json errors.
  - Test: `ComparisonApiIT` end to end on the seeded factory, including the values-equal-results assertion and the edited-scenario case.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.6, 6.1_

- [ ] 7. Expose `GET /api/scenarios/{id}/comparisons`
  - Baseline discovery by `base_state_hash`; `baselineMissing` and `suggestedBaselineScenarioId`.
  - Test: `ComparisonApiIT` for found, missing and not-yet-run baselines and for a scenario with no run.
  - _Requirements: 7.1, 7.2, 7.3_

- [ ] 8. Add the actual-outcome block (M8)
  - `ActualOutcomeService`, `includeActual` flag.
  - Test: `ActualOutcomeIT`.
  - _Requirements: 8.1, 8.2_

- [ ] 9. Document the endpoints
  - OpenAPI annotations; Comparison section in `docs/api.md` explaining `sameStartingState`, the warnings and why cost per stone accompanies total cost.
  - _Requirements: 1.3, 2.6_
