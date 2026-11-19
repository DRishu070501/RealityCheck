# Method: from a real factory to a fictional one

RealityCheck's demonstration factory is fictional, but its shape comes from one real diamond factory. This
page describes how the real data was measured, how it was kept private, and what the model leaves out.
[demo-data.md](demo-data.md) has the resulting figures, and [accuracy.md](accuracy.md) has how well the
simulation reproduces history.

## Pipeline

```
real factory database (MySQL, read-only session, credentials outside the repository)
   │  read-only queries, one month of movements, history and dispatches
   ▼
extracts (CSV, outside the repository)  +  department grouping (local/, confirmed by the factory's team)
   │  tools/calibration/calibrate.py
   ▼
local/factory-a.calibration.json   detailed, never committed
   │  tools/calibration/publish.py   (generic stage codes, rounded, no names, ids or dates; refuses known names)
   ▼
backend/src/main/resources/demo/calibration.json   committed
   │  CalibratedDemoGenerator   (adds synthetic names, volume, people, machines, costs)
   ▼
"Surat Diamond Works": seeded through the same services as the API
```

A second path checks the model against the real history without publishing it: `tools/import` loads an
anonymised copy into a private local instance and backtests it (see [accuracy.md](accuracy.md)).

## What was measured

- **Grouping.** About 270 departments, grouped into a dozen stages. The kinds are: inward (where lead time
  starts), working stages, parking (held in the rough store), outside job work, waiting for dispatch, and the
  end points. The factory's team confirmed the grouping.
- **Pieces.** Every piece dispatched in the month, traced back through its parent packets to its lot's
  arrival, about 30,000 pieces. Lead time runs from that arrival to the first end department or the final
  dispatch.
- **Routes.** Two packet families: one goes through polishing to dispatch, the other leaves after recut. Each
  route records the share of pieces visiting each optional step (parking, job work, lab).
- **Time.** The deciles of time at each stage. A working stage's stay is split into processing (from capacity)
  and a handling delay (the rest). Holding stages hold a piece for a delay drawn from their deciles.
- **Capacity.** A busy day's output per stage (90th percentile of pieces leaving per working day), compared
  with a typical day's. Workers hold packets in parcels, many at once, so worker-hours are not a capacity
  measure.
- **Yield.** Carat weight returned over issued per stage.

The engine has no random numbers. A piece's delay, and whether it takes an optional step, come from a hash of
its id, so the measured spread is reproduced and the same input always gives the same result.

## Privacy

| Rule | How it is kept |
|---|---|
| The source database is only read | Read-only sessions; the calibration tool never connects at all, it reads extracts. |
| Credentials stay out of the repository | Read from a file path outside it; `*.cnf` and `local/` are ignored by git. |
| No names leave the machine | Departments, people, parties, lots and packets never appear in committed files. Before writing, `publish.py` and the importer check their output against a local list of real names, and a test does the same when that list is present. |
| Only rounded aggregates are published | The committed calibration has generic stage codes, rounded figures and no dates. `accuracy.md` gives rounded errors without dates. |
| Personal data is not read | User tables were not queried; workers appear only as counts per day. |

## Limits

- **One factory.** Only factory A is calibrated. A second factory's data was extracted, but its department
  grouping was not confirmed in time, so it is not used.
- **One month, in sample.** The calibration and the real-data backtest use the same month.
- **Splitting is yield.** A packet that splits into pieces is modelled as one batch losing weight. Pieces are
  not modelled individually, so early stages carry one batch per eventual piece in the real-data backtest.
- **Repairs are out of scope.** Pieces returned for repair after dispatch are not modelled.
- **Shifts are assumed.** The real shift pattern is not in the data; Monday to Saturday, 09:00 to 18:00, is
  used.
- **Handling delays do not respond to staffing.** Adding people changes capacity, not the handling delay, so
  "more people" helps only where a stage runs near capacity. A scenario that adds people to a stage that is
  mostly handling time will show little change, and that is the model, not a bug.
- **Stays are scaled to fit lead time.** Per-stage stays pool every visit, so in the demonstration factory
  they are scaled by two fitted factors to match the measured lead times (see [demo-data.md](demo-data.md)).
- **Work in progress at the fork.** A piece already at a working stage on the simulated date starts its
  handling delay again from that date. This is the most likely cause of the simulation holding too many pieces
  at early stages (see [accuracy.md](accuracy.md)).
- **Synthetic everything else.** Names, volume, people, machines, costs and stone yields in the demonstration
  factory are chosen, not measured. They are not industry benchmarks.
