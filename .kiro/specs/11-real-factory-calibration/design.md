# 11 Real-Factory Calibration and Kiroween Release — Design

## Data flow

```
Real factory DB (MySQL, read-only, local only)
   │  tools/calibration (Python, read-only queries, credentials outside the repo)
   ├─► local/factory-X.calibration.json        (detailed; never committed)
   ├─► local backtest inputs                   (anonymised codes; never committed)
   └─► backend/src/main/resources/demo/calibration.json   (merged, rounded, no identifiers; committed)
                                    │
                                    ▼
          demo.CalibratedDemoGenerator ──► seeded fictional factory, history, scenarios
                                    │
                                    ▼
          engine 2.0.0: routes, holding stages, job-work stages, worker pools
                                    │
              ┌─────────────────────┼─────────────────────────┐
              ▼                     ▼                         ▼
        Backtest service      Insights service         Ghost futures / haunted stages
```

## Real-factory model (from factory A, October 2026; confirmed by the team)

| Real concept | RealityCheck model |
|---|---|
| Inward department ("Lot Creation") | Arrival of a batch; lead time starts here |
| Parking department | Holding stage `PARKING`; excluded from manufacturing lead time |
| Working departments of the factory company, and stock-controller departments | Working stages; capacity = worker pool |
| Departments of third-party companies | Job-work stages: outside delay, no internal capacity |
| Dispatch departments before final dispatch | Holding stage `DISPATCH_WAIT` |
| Packet types | Routes |
| Weight issued vs returned per process | Yield per stage |
| Packet splitting | Represented as yield in this release; individual pieces are not modelled |
| Repairs after dispatch | Out of scope; documented as a limitation |

Measured in factory A (rounded; used to shape, not copied): median lead time about 20 days with parking and
15 without; about half of the rough parked, median about 9 days; finished stones wait about 5 days before
dispatch; job work about 15% of manufacturing time; recut loses about 16% of weight; working time with a
worker is a small part of time in a working department.

## Engine changes

- `Route` (ordered stage ids) per packet type in the snapshot; a batch carries its packet type.
- `StageKind`: `WORKING` (existing behaviour), `HOLDING` (delay, no capacity), `JOB_WORK` (delay, no
  capacity, counted separately). Delays are drawn from a per-stage empirical distribution (quantile table)
  with the run's deterministic random stream, so a run stays reproducible.
- Metrics gain `holdingSeconds`, `jobWorkSeconds` and `manufacturingLeadTime` (excluding holding).
- Algorithm version 2.0.0. A factory with one route and only working stages produces 1.0.0 output, checked
  by an equivalence property test.

## Backtest

`simulation.backtest.BacktestService`: for each fork date, build the baseline snapshot, run the engine with
no changes, and compare with recorded history over [fork, min(simulated end, last recorded event)] through
the existing `ActualOutcomeService` plus per-stage work in progress from `FactoryStateService`. Results are
stored like other runs and served by `GET /api/backtests/{id}`.

## Privacy controls

- `tools/calibration` lives in the repository as code only. It refuses any statement that is not a read,
  reads credentials only from `TRYBE_DB_CNF` (a path), and writes only to a directory outside the repository.
- A test scans the committed calibration file and seed for any string found in the local department, party
  and user name lists when those lists are present; in CI they are absent and the test checks structure only.
- `.gitignore` excludes `local/`, `*.cnf` and `*.calibration.json` outside `resources/demo/`.

## Public deployment

One small cloud server running the existing Docker Compose stack behind HTTPS. Demo reset disabled in the
UI; a nightly job calls the reset endpoint locally. AI: Claude with requests per minute capped and a new daily
spending cap (`AI_DAILY_BUDGET_USD`) after which the built-in analyst answers.
