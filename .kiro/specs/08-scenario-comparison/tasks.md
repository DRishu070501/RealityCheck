# 08 Scenario Comparison — Tasks

Milestone: **M5 — Scenario branching** (tasks 1-7). Task 8 is **M8**. Depends on spec 07 Part B.

- [x] 1. Implement `DeltaCalculator`
  - Absolute, percent and direction functions.
  - Test: `DeltaCalculatorTest` and the jqwik antisymmetry property.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 2. Define `RunResultsPort` and implement it in `simulation`
  - `RunView` record (metadata, summary, stage, resource and order results, applied changes from the snapshot); `SimulationQueryService` implements the port.
  - Test: integration test that `RunView` for a completed run matches the run's API responses.
  - _Requirements: 1.1, 6.1_

- [x] 3. Implement `MetricCatalog` and summary comparison
  - Catalog entries with extractor, unit and `betterWhen`; build `MetricDelta` list per alternative.
  - Test: every catalog key resolves on a real `RunView`; self-comparison is all zero and `UNCHANGED`.
  - _Requirements: 2.1, 2.4, 2.5, 2.6_

- [x] 4. Implement comparability checks and warnings
  - The check in design.md with the three warning codes.
  - Test: `ComparisonServiceTest` for each branch.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

- [x] 5. Implement stage, resource, order and bottleneck comparison
  - Join by stage id, pool id and order id with one-sided nulls; bottleneck change table.
  - Test: `ComparisonServiceTest` for one-sided entries, each `change` value, unfinished orders.
  - _Requirements: 3.1, 3.2, 3.3, 4.1, 4.2, 5.1, 5.2_

- [x] 6. Expose `POST /api/simulations/compare`
  - Request validation, response assembly, problem+json errors.
  - Test: `ComparisonApiIT` end to end on the seeded factory, including the values-equal-results assertion and the edited-scenario case.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.6, 6.1_

- [x] 7. Expose `GET /api/scenarios/{id}/comparisons`
  - Baseline discovery by `base_state_hash`; `baselineMissing` and `suggestedBaselineScenarioId`.
  - Test: `ComparisonApiIT` for found, missing and not-yet-run baselines and for a scenario with no run.
  - _Requirements: 7.1, 7.2, 7.3_

- [x] 8. Add the actual-outcome block (M8)
  - `ActualOutcomeService`, `includeActual` flag.
  - Test: `ActualOutcomeIT`.
  - _Requirements: 8.1, 8.2_

- [x] 9. Document the endpoints
  - OpenAPI annotations; Comparison section in `docs/api.md` explaining `sameStartingState`, the warnings and why cost per stone accompanies total cost.
  - _Requirements: 1.3, 2.6_

## Implementation notes (Milestone 5)

Tasks 1-7 and 9 are built; task 8 (actual outcome) is Milestone 8. Differences from design.md:

- `RunResultsPort` (declared in `scenario.comparison`, implemented by `simulation.service.SimulationQueryService`)
  returns typed `RunView`/`SummaryView` records; `MetricCatalog` extractors read those fields.
- `includeActual: true` is refused with 400 until task 8 exists, rather than silently returning no block.
- Percentages divide by the absolute value of the baseline (all compared metrics are non-negative, so this
  equals the design's formula for every real value).
- `GET /api/scenarios/{id}/comparisons` picks, for each baseline scenario, its newest completed run **with the
  same starting-state hash** (not simply its newest run), and suggests a baseline scenario only if it has the
  same fork and the same `includeFutureOrders`.
- Tests: `DeltaCalculatorTest` (with jqwik antisymmetry and self-comparison properties),
  `ComparisonServiceTest` (hand-built runs: every comparability branch and warning, the bottleneck table,
  one-sided pools, unfinished orders), `DemoScenariosIT` (end to end on the seeded factory, values equal each
  run's own results, applied changes come from the snapshot after the scenario is edited, different start
  refused then allowed with a warning).

## Implementation notes (Milestone 8)

Task 8 is built. `ActualOutcomeService` (in `scenario.comparison`) reads the factory state rebuilt at the latest
recorded event through `production.FactoryStateService` (corrections applied) and `ProductionReader.lastEventAt`.
Scope: the orders in the baseline run; output counts final-stage completions after the fork by batches that were
unfinished at the fork, which is the work the simulation covers. An order is completed when every one of its
batches finished the last stage; its time is the last of those. `actual` is null when not requested or nothing was
recorded after the fork; nothing is subtracted from simulated values. Tests: `ActualOutcomeServiceTest` (no history
after the fork) and `DemoScenariosIT.showsWhatRecordedHistorySaysHappenedAfterTheForkBesideTheSimulation`, which
checks every figure against independent SQL over `operational_event` and `production_order` and that history is
unchanged. The Simulation Results screen requests it and shows "What actually happened after the fork" beside the
baseline's simulated figures.

The 409 for an unfinished run now names the run's state `runStatus` (it was `status`, which overwrote the
problem's HTTP status); the same fix was made in `SimulationQueryService`.
