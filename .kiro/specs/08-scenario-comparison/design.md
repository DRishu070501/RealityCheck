# 08 Scenario Comparison — Design

## Components

Package `com.realitycheck.scenario` (architecture.md places comparison here):

| Class | Responsibility |
|---|---|
| `api.ComparisonController` | `POST /api/simulations/compare`, `GET /api/scenarios/{id}/comparisons`. |
| `api.CompareRequest`, `ComparisonResponse`, `MetricDelta`, `StageComparison`, `ResourceComparison`, `OrderComparison`, `BottleneckChange`, `ComparedRun`, `ActualOutcome` | DTO records. |
| `comparison.ComparisonService` | Loads runs through `RunResultsPort`, checks comparability, builds the response. |
| `comparison.MetricCatalog` | The fixed list of compared metrics: key, label, unit, `betterWhen`, and an extractor from the run summary. One place defines what is compared. |
| `comparison.DeltaCalculator` | Pure functions for absolute and percentage difference and direction. |
| `comparison.RunResultsPort` | Interface declared here, implemented in `simulation` by `SimulationQueryService`: `RunView load(UUID runId)`, `Optional<RunView> latestCompleted(UUID scenarioId)`. `RunView` carries run metadata, summary, stage and resource results, order results and the applied changes from the snapshot. |
| `comparison.ActualOutcomeService` | Builds the `actual` block from recorded events after the fork (via `production`'s read services). |

The port keeps the module direction of architecture.md: `simulation` depends on `scenario`, not the reverse.

No new tables.

## Comparability check

```
check(baseline, alternatives, allowDifferentBaseline):
  all runs exist                                   else 404
  all share factory_id                             else 400
  all COMPLETED                                    else 409
  for alt in alternatives:
     same = alt.forkAt == baseline.forkAt && alt.baseStateHash == baseline.baseStateHash
     if !same && !allowDifferentBaseline: 422 DIFFERENT_STARTING_STATE (lists the differing run ids)
     if !same: warnings += DIFFERENT_STARTING_STATE(alt)
     if alt.algorithmVersion != baseline.algorithmVersion: warnings += DIFFERENT_ALGORITHM_VERSION(alt)
  for run in all: if run.terminationReason != COMPLETED: warnings += INCOMPLETE_SIMULATION(run)
```

`base_state_hash` (spec 06) covers both reconstructed work in progress and captured configuration, so "same
starting state" means the two runs differ only by their scenario changes.

## Metric catalog

| Key | Unit | `betterWhen` |
|---|---|---|
| `elapsedSeconds` | seconds | LOWER |
| `processingSeconds` | seconds | — |
| `waitingWorkingSeconds` | seconds | LOWER |
| `throughputStonesPerHour` | stones/hour | HIGHER |
| `outputStones` | stones | HIGHER |
| `outputCarats` | carats | HIGHER |
| `cost.total` | currency | LOWER |
| `cost.perOutputStone` | currency/stone | LOWER |
| `cost.employee`, `cost.machine`, `cost.fixed` | currency | LOWER |
| `machineUtilization`, `employeeUtilization` | fraction | — |
| `ordersCompleted` | orders | HIGHER |
| `ordersDelayed` | orders | LOWER |
| `onTimeDeliveryRate` | fraction | HIGHER |

`DeltaCalculator`:

```
absolute(b, a)  = (a == null || b == null) ? null : a - b
percent(b, a)   = (a == null || b == null || b == 0) ? null : (a - b) * 100 / b     scale 2 HALF_EVEN
direction(m, d) = m.betterWhen == null || d == null ? null
                : d == 0 ? UNCHANGED
                : (d < 0) == (m.betterWhen == LOWER) ? IMPROVED : WORSENED
```

"Improved" describes the metric's movement under the simulation's assumptions. It is not a recommendation
and never a claim about real-world savings; the UI wording follows product.md.

Because scenarios of different length accrue cost over different horizons (simulation-rules.md section 11),
`cost.total` and `cost.perOutputStone` are always shown together.

## Bottleneck change

```
b = baseline.primaryStageId, a = alternative.primaryStageId
change = NONE     if b == null && a == null
       = SAME     if b == a
       = RESOLVED if b != null && a == null
       = EMERGED  if b == null && a != null
       = MOVED    otherwise
```

For `MOVED` the response includes `score` of both stages in both runs, read from the stage results.

## API

`POST /api/simulations/compare`

```json
{ "baselineRunId": "…", "alternativeRunIds": ["…"], "allowDifferentBaseline": false, "includeActual": false }
```

→ 200

```json
{ "source": "SIMULATED", "currencyCode": "INR", "sameStartingState": true, "forkAt": "…",
  "warnings": [],
  "baseline": { "runId": "…", "scenarioId": "…", "scenarioName": "Current factory operations",
                "algorithmVersion": "1.0.0", "terminationReason": "COMPLETED", "appliedChanges": [] },
  "alternatives": [
    { "run": { "runId": "…", "scenarioId": "…", "scenarioName": "Add two polishing machines",
               "algorithmVersion": "1.0.0", "terminationReason": "COMPLETED",
               "appliedChanges": [ { "ordinal": 1, "changeType": "RESOURCE_CAPACITY", "summary": "Polishing wheels: capacity +2 units" } ] },
      "metrics": [
        { "key": "elapsedSeconds", "label": "Completion time", "unit": "seconds",
          "baseline": "432000", "alternative": "388800", "absolute": "-43200", "percent": "-10.00",
          "betterWhen": "LOWER", "direction": "IMPROVED" } ],
      "bottleneck": { "change": "MOVED",
                      "baselineStage": { "stageId": "…", "code": "POLISHING", "baselineScore": "0.812000", "alternativeScore": "0.401000" },
                      "alternativeStage": { "stageId": "…", "code": "QUALITY_INSPECTION", "baselineScore": "0.377000", "alternativeScore": "0.655000" } },
      "stages": [ { "stageId": "…", "code": "POLISHING", "sequence": 6,
                    "meanWaitWorkingSeconds": { "baseline": "5400.0000", "alternative": "1200.0000", "absolute": "-4200.0000", "percent": "-77.78" },
                    "meanQueueLength": { … }, "maxQueueLength": { … }, "throughputStonesPerHour": { … },
                    "limitingUtilization": { … }, "bottleneckScore": { … } } ],
      "resources": [ { "resourceId": "…", "name": "Polishing wheels", "kind": "MACHINE",
                       "capacity": { "baseline": "6", "alternative": "8", "absolute": "2", "percent": "33.33" },
                       "utilization": { … }, "busyUnitSeconds": { … }, "idleUnitSeconds": { … }, "cost": { … } } ],
      "orders": [ { "orderId": "…", "orderNumber": "SDW-2026-0004", "dueAt": "…",
                    "baselineCompletionAt": "…", "alternativeCompletionAt": "…", "differenceSeconds": -86400,
                    "baselineOutcome": "LATE", "alternativeOutcome": "ON_TIME" } ] } ],
  "actual": null }
```

(The numbers illustrate the shape only.) Numeric values are strings, as elsewhere.

`GET /api/scenarios/{id}/comparisons` → `{ "baselineMissing": false, "suggestedBaselineScenarioId": null, "items": [ ComparisonResponse … ] }`.
The scenario itself being a baseline returns an empty list (nothing to compare against).

`actual` block when requested:

```json
{ "source": "ACTUAL", "asOf": "2026-01-19T11:55:00Z",
  "ordersInScope": 9, "ordersCompleted": 6, "outputStones": "1104.0000", "outputCarats": "672.3000",
  "orders": [ { "orderId": "…", "orderNumber": "…", "completedAt": "…" } ] }
```

## Error cases

| Case | Status |
|---|---|
| Empty or more than five alternatives, baseline also listed as alternative, runs from different factories | 400 |
| Unknown run | 404 |
| A run not `COMPLETED` | 409 |
| Different starting state without consent | 422 `DIFFERENT_STARTING_STATE` |

## Testing

- `DeltaCalculatorTest`: nulls, zero baseline, negative and positive differences, rounding, direction for both `betterWhen` values and for none.
- jqwik: `absolute(b, a) == -absolute(a, b)`; comparing a run with itself yields zero differences and `UNCHANGED` everywhere and `SAME` or `NONE` bottleneck change.
- `ComparisonServiceTest` (Mockito port): each comparability branch and warning; bottleneck change table; stage or pool present on one side only.
- `ComparisonApiIT` (Testcontainers, seeded factory): run the baseline and "Add two polishing machines", compare; every `baseline` and `alternative` value in the response equals the value in the corresponding `/results`, `/stages` and `/resources` response; applied changes come from the snapshot (edit the scenario after the run and assert the comparison still shows the original change); `GET /api/scenarios/{id}/comparisons` finds the baseline; different-fork runs → 422, then 200 with warning when allowed.
- `ActualOutcomeIT`: counts equal direct SQL over events after the fork.
