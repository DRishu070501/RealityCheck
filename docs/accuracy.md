# How accurate is the simulation?

RealityCheck answers "what happens if…" by simulating the factory forward from a date. Before trusting an
answer, it is fair to ask how well the simulation predicts what really happened when nothing was changed.
A **backtest** does exactly that: it simulates from a past date with no changes and compares the result with
the recorded history over the same window, for the same packets. The Accuracy screen runs one on any factory;
this page records the results on the demonstration factory and on one real factory.

All figures below are rounded. The real factory's detailed results, its data and its name stay on the machine
that ran the import (see [tools/import](../tools/import/README.md)).

## What is compared

For each date:

- **Packets finished** in the window, simulated against recorded.
- **Median lead time** of those packets, from the date (or their arrival, if later) to finishing.
- **Work in progress by stage** at the end of the window. The error is the number of packets the simulation
  places at a different stage from the record, over the number recorded in progress.

Errors are signed (simulated minus recorded, over recorded). The **typical error** is the median of the
absolute errors across dates: neither the best nor the worst date.

## Demonstration factory

Four dates spread over its seeded history; each window runs to the last recorded event.

| Measure | Typical error |
|---|---|
| Packets finished | 19% |
| Median lead time | 4% |
| Work in progress by stage | 40% |

The demonstration factory is generated from the calibration, so lead time is close by construction. Work in
progress is not: the simulation queues packets at polishing that the generated history had already moved on.

## A real diamond factory (factory A)

**Data.** One month of a real factory's history: every piece dispatched in that month, traced back to its lot's
arrival. One lot in five was imported (whole lots, chosen by a hash), about 2,900 lots and 6,000 pieces, as
about 100,000 events through the public API into a private local instance. Lots became codes and departments
became generic stages. Routes, visit shares, capacity (the busy-day output of each working stage) and every
delay were measured on the same imported pieces.

**Test.** Four dates one week apart in that month. Each window is **seven days**.

| Date | Pieces compared | Packets finished | Median lead time | Work in progress by stage |
|---|---|---|---|---|
| First | about 2,800 | +19% | −28% | 39% |
| Second | about 4,000 | −2% | −46% | 64% |
| Third | about 3,500 | −31% | −44% | 65% |
| Fourth | about 1,800 | −27% | −55% | 111% |
| **Typical** | | **19%** | **44%** | **64%** |

Work in progress at the end of the four windows, added together:

| Stage | Simulated | Recorded |
|---|---|---|
| Imaging | about 1,100 | about 140 |
| Lab | about 320 | about 300 |
| Inspection | about 650 | about 240 |
| Planning | about 1,000 | about 820 |
| Parking | about 1,400 | about 1,300 |
| Sawing | about 310 | about 600 |
| Job work | about 400 | about 680 |
| Polishing | about 670 | about 740 |
| Recut | about 2,100 | about 2,200 |
| Dispatch waiting | about 100 | about 180 |

**What it says.**

- The **shape** is right where delays dominate: parking, polishing and recut hold about as many pieces as
  recorded, and the number finished in a week is within about a fifth on a typical date.
- The simulation **finishes pieces faster** than recorded (lead time 30–55% short), and it **holds too many at
  imaging and inspection and too few at sawing and job work**. Capacity in this release is a busy-day output
  per stage, and a handling delay is drawn per piece. A piece already in progress at a working stage on the
  date starts its handling delay again from the date, and pieces are not split. These are the engine's known
  simplifications, and the most likely places to improve it.
- This is **not tuned**. The import measures the factory once and the backtest reports what it gets.

**Read with care.**

- Only pieces dispatched in the month are in the data, so every imported piece finishes within it. Compared up
  to the last recorded event instead of over seven days, the recorded side has nothing left in progress and
  every piece finished. The comparison then says little: the simulation finishes about a third fewer pieces
  (typical error 36%), and work in progress cannot be scored at all. The seven-day window avoids this.
- Before a lot splits, each of its pieces is a separate batch, so early stages see more batches than physical
  packets. Capacity is measured on the same batches, so the two agree.
- Shifts were not in the data; Monday to Saturday, 09:00 to 18:00, is assumed.
- The calibration and the backtest use the same month, so this is an in-sample check of the model's
  structure, not a forecast of an unseen month.

## Run it yourself

On any factory with recorded history, open **Accuracy** and choose **Run a backtest**, or call
`POST /api/backtests` (see [api.md](api.md#backtest)) with optional `forkAts` and `horizonDays`.
