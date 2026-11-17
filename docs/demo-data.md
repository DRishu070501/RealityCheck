# Demonstration data

**Calibrated, synthetic data.** "Surat Diamond Works" is a fictional factory. Its shape and its waiting
patterns are calibrated on rounded measurements from one real diamond factory (October 2026): which stages
packets pass through, on which routes, how often, how long they are parked, sent out to job workers, held
before dispatch and left at each stage, and how close each stage runs to its capacity. Every name, and every
capacity, rate, stone yield and cost, is synthetic. Nothing here identifies the real factory, and nothing
should be read as an industry benchmark.

The calibration is `backend/src/main/resources/demo/calibration.json`, produced by
`tools/calibration/publish.py` (spec 11): generic stage names, figures rounded, no names, identifiers or
dates. The factory is built from it by `CalibratedDemoGenerator` and created through the same services the
API uses, so it passes the same validation as user input.

## How it is created and reset

| Setting | Effect |
|---|---|
| `REALITYCHECK_DEMO_SEED=true` | At start-up, seed the factory if the database has no factory. Existing data is never touched. |
| `REALITYCHECK_DEMO_RESET_ENABLED=true` | Registers `POST /api/demo/reset` and the Settings screen's reset action. It **erases all data** and seeds again. |

Both are `true` in `.env.example` and `false` when unset. Turn both off for any deployment that holds data
you want to keep.

The seed is deterministic **for a given seeding instant**. Ids are derived from fixed keys, every choice
about a packet comes from a fixed hash of its number, and every timestamp is an offset from an anchor: 09:00
Asia/Kolkata on the Monday two weeks before the week in which the seed runs. History is generated from the
anchor up to the seeding instant and no further, so the factory always has two to three weeks of past and
work in progress "now". Seeding twice for the same instant produces identical rows; an integration test
compares a digest of every table. Resetting on a later day produces a history that ends on that day.

The demonstration does not move on by itself. A week after seeding, the work in progress is a week stale;
reset to bring it up to date.

## Factory

| | |
|---|---|
| Name | Surat Diamond Works |
| Time zone | Asia/Kolkata |
| Currency | INR |
| Working week | Monday to Saturday, 09:00 to 18:00, break 13:00 to 14:00. Sunday closed. |
| Working time | 8 hours a day, 48 hours a week |

## What comes from the calibration, and what is chosen

| From the measured factory (rounded) | Chosen for the demonstration (synthetic) |
|---|---|
| Stages, their order and kinds | Ten packets a working day, 8 to 20 stones each |
| Two routes and their shares: Tops 55%, Full manufacturing 45% | Names of pools, people and machines, and how many |
| Share of packets visiting each optional step | Hourly and fixed costs |
| Parking, job-work and dispatch-waiting times (ten values each) | Stone yields at sawing and polishing (99%) |
| Time packets stay at each working stage (ten values each) | |
| Carat yields | |
| How close each working stage runs to capacity on a typical day | |

Two derived choices:

- **Rates.** Each working stage's rate is set so that, at ten packets a day and with the people chosen, it
  runs at the measured typical load (Lab and Polishing near capacity at 85%, Imaging and Recut at 60%). The
  intake desk, which has no measured load, runs at 50%.
- **Handling delays.** A working stage's measured stay is split into processing (from the rate) and a
  handling delay after it (the rest), which the engine models as time the packet stays with its people
  already free. Per-stage stays pool every visit by every packet, so they do not add up to a packet's lead
  time on their own: stays at the stages both routes share are scaled by 0.55 and at polishing by 3.0
  (polishing is several departments a Full packet passes through, some more than once). The two factors were
  fitted once so that the overall and per-route median lead times are within 15% of the measured ones; a
  unit test and an integration test check that they still are.
- **Tops parking.** The measured Tops route lists only steps at least half its packets visit, so parking is
  not on it; but 45% of all packets are parked. Tops packets are parked 41% of the time so that the overall
  share is 45% (Full: 50%).

## Resource pools

A pool is a group of interchangeable units. Cost is per unit per hour, in INR.

| Kind | Pool | Category | Units | Hourly cost |
|---|---|---|---|---|
| Employee | Intake clerks | `INTAKE_CLERK` | 2 | 110.00 |
| Employee | Imaging operators | `IMAGING_OPERATOR` | 3 | 180.00 |
| Employee | Lab graders | `LAB_GRADER` | 4 | 260.00 |
| Employee | Inspectors | `INSPECTOR` | 3 | 220.00 |
| Employee | Planners | `PLANNER` | 4 | 300.00 |
| Employee | Saw operators | `SAW_OPERATOR` | 4 | 240.00 |
| Machine | Laser saws | `LASER_SAW` | 4 | 300.00 |
| Employee | Polishers | `POLISHER` | 8 | 230.00 |
| Machine | Polishing wheels | `POLISHING_WHEEL` | 4 | 140.00 |
| Employee | Recut workers | `RECUT_WORKER` | 2 | 230.00 |

One availability window is seeded: two of the four lab graders are away for the whole working day on the
Wednesday of the first week (09:00 to 18:00 local), reason "Two graders on leave (synthetic)". It appears in
the history as a `RESOURCE_UNAVAILABLE` event.

## Workflow: Rough to polished

Rate is stones per hour **per processing slot**; setup is 10 minutes per job. Times are calendar hours,
given as the 1st, 5th and 10th of ten values (the engine gives each packet one of the ten).

| # | Stage | Kind | Rate | One slot needs | Slots | Held or handled (h) | Carat yield |
|---|---|---|---|---|---|---|---|
| 1 | Rough intake | Working | 22.1 | 1 intake clerk | 2 | 0, 0, 0.9 | 100% |
| 2 | Imaging and scanning | Working | 11.0 | 1 imaging operator | 3 | 1.9, 13.4, 36.5 | 100% |
| 3 | Lab grading | Working | 5.16 | 1 lab grader | 4 | 0, 6.5, 38.9 | 100% |
| 4 | Inspection | Working | 9.25 | 1 inspector | 3 | 0, 11.5, 44.0 | 100% |
| 5 | Planning | Working | 6.27 | 1 planner | 4 | 3.7, 23.5, 74.1 | 100% |
| 6 | Rough store (parked) | Parking | | | | 12, 168, 1,577 | 100% |
| 7 | Laser sawing | Working | 6.27 | 1 laser saw, 1 saw operator | 4 | 0, 8.1, 70.2 | 98% |
| 8 | Outside job work | Job work | | | | 15, 25, 424 | 99% |
| 9 | Polishing | Working | 2.38 | 1 polishing wheel, 2 polishers | 4 | 0, 195, 978 | 98% |
| 10 | Recut | Working | 17.6 | 1 recut worker | 2 | 0, 1.8, 41.4 | 89% |
| 11 | Dispatch | Waiting | | | | 0, 2, 47 | 100% |

| Route | Share | Steps (visit share when below 100%) |
|---|---|---|
| Tops | 55% | Intake, Imaging, Lab (90%), Inspection, Planning, Parking (41%), Sawing, Job work (50%), Recut |
| Full manufacturing | 45% | Intake, Imaging, Lab, Inspection, Planning, Parking (50%), Sawing, Job work (70%), Polishing, Recut, Dispatch |

What the engine reproduces, from an empty factory with sixty working days of packets (`DemoCalibrationTest`;
simulated): median lead time 16.7 days (measured 17.0) and 10.8 without parking (measured 11.5); Tops 9.1 and
7.3 (measured 8.5 and 7.0); Full 24.5 and 20.5 (measured 26.5 and 20.0).

## Lots and history

Lots (orders) and packets (batches) are generated by `ProductionSeedSection`.

| | |
|---|---|
| Arrival | One lot every two working hours from the anchor (09:00, 11:00, 14:00, 16:00): `SDW-0001`, ... |
| Packets | Two or three per lot, alternately: about ten a working day |
| Route | Tops for 55% of packets, Full manufacturing for the rest |
| Packet size | 8 to 20 stones, at 0.90 to 1.50 rough carats per stone |
| Priority | 1 to 5, spread evenly |
| Due | 28 to 35 days after placing |
| Customer | "Demo buyer A" to "Demo buyer F" |

A seed run mid-week in the third week gives about 58 lots and 145 packets.

The history is produced by a small first-come-first-served scheduler that respects the working calendar and
every pool's capacity, including the graders' absence, so the recorded past is one that could have happened:
an integration test checks that no pool is ever shown using more units than it has. At an optional step a
packet is sent on or not; at a working stage it waits for a free slot, is processed, then stays for a
handling time; at a parking, job-work or dispatch stage it is held. Packets move only in working hours.
Durations are drawn from the same calibrated figures but **independently of the simulation engine**: a point
within each tenth rather than the tenth itself, its own choice of optional steps, and processing between 8%
faster and 15% slower than the rate. Recorded stone counts are whole stones. Most finished lots are
dispatched two working hours later; the first of every three to finish is left finished and waiting. One sawing completion is
recorded a stone too high and corrected an hour later, so that the history contains a correction.

The scheduler and the engine are separate on purpose, so that simulated results can be compared with
something the engine did not itself produce.

## Scenarios

Six scenarios, all forked at the same instant: five working days (Monday to Saturday) before the seeding
instant, at the same time of day, so recorded history exists after the fork. They are chosen to show what the
calibration found. They are created through the scenario service, so their changes pass the same
validation as user input. **No result is seeded**: every number shown for them is computed when they are run.

| Scenario | Change |
|---|---|
| Current factory operations | None: the baseline |
| Park half as long | Rough store: every parking time halved (`STAGE_DELAY` -50%) |
| Three more inspectors | Inspectors +3 (3 to 6). Inspection runs at 70% of capacity, so packets there wait mostly for other reasons |
| Job worker 30% slower | Outside job work: every time 30% longer (`STAGE_DELAY` +30%) |
| Increase production demand by 20% | Stones and carats of every packet not yet started +20% |
| Introduce an additional shift | Monday to Saturday 18:00-22:00 added, every pool at normal capacity |

Changing people or machines changes capacity only. It does not change parking, job-work, dispatch or
handling times, which were measured, not derived from capacity: more people help only where a stage runs
near its capacity.
