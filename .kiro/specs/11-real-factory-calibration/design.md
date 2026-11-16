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

Factory B is deferred: its database is reachable and October 2026 is extracted locally, but its department
grouping is not confirmed, so this release calibrates on factory A alone and says so.

Measured in factory A (rounded; used to shape, not copied): median lead time about 20 days with parking and
15 without; about half of the rough parked, median about 9 days; finished stones wait about 5 days before
dispatch; job work about 15% of manufacturing time; recut loses about 16% of weight; working time with a
worker is a small part of time in a working department.

## Engine changes

- `Route` (ordered stage ids) per packet type in the snapshot; a batch carries its packet type.
- `StageKind`: `WORKING` (existing behaviour), `HOLDING` (delay, no capacity), `JOB_WORK` (delay, no
  capacity, counted separately). The engine has no random stream and must not gain one: a delay is picked
  from the stage's quantile table (deciles, in working seconds) by `hash(batchId) mod 10`, so the spread of
  real delays is reproduced and the same input always gives the same output.
- Route steps carry a `visitShare` (published calibration): a batch visits an optional step (parking, job
  work) when `hash(batchId, stage) mod 100 < visitShare × 100`, so the measured share of pieces takes it.
- Progression today is `stageIndex + 1` (`SimulationEngine.java:199`); it becomes a lookup in the batch's
  route. A snapshot with no routes gets one implicit route over all stages in sequence.
- **Known modelling gap, decided by the offline backtest (task 4):** in factory A a packet spends about
  1.5 days in the checking department but under an hour with a worker. Capacity queuing alone will not reproduce that, so
  working stages may need a calibrated per-stage handling delay (deterministic, as above) on top of
  processing. If the backtest without it is within tolerance, it is not added.
- **Decided (task 4, 16 November):** added. Workers hold packets in parcels, many at once, so time with a
  worker is not processing time and worker-hours are not a capacity measure. Capacity is therefore modelled
  as packets per day: a stage's busy-day output (90th percentile of daily output) divided among its workers.
  On a typical day, Lab and Manufacturing run at 86–87% of busy-day output (near capacity), while Planning,
  Quality check, Imaging and Recut run at 59–70% yet hold packets for one to two days. Queuing on capacity
  cannot produce that, so each working stage gets a calibrated handling delay: dwell deciles minus the
  queuing the engine already produces. Scenario changes to people change capacity, not the handling delay;
  the docs say so, because it means "more people" helps only where a stage is near capacity.
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
