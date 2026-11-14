# Demonstration data

**Synthetic data.** "Surat Diamond Works" is a fictional factory. Every capacity, rate, yield and cost
below was chosen to make a plausible, internally consistent example with a visible constraint at polishing.
None of it is an industry benchmark, and nothing here should be read as typical of a real manufacturer.

The data is defined in `backend/src/main/java/com/realitycheck/demo/DemoData.java` and created through the
same services the API uses, so it passes the same validation as user input.

## How it is created and reset

| Setting | Effect |
|---|---|
| `REALITYCHECK_DEMO_SEED=true` | At start-up, seed the factory if the database has no factory. Existing data is never touched. |
| `REALITYCHECK_DEMO_RESET_ENABLED=true` | Registers `POST /api/demo/reset` and the Settings screen's reset action. It **erases all data** and seeds again. |

Both are `true` in `.env.example` and `false` when unset. Turn both off for any deployment that holds data
you want to keep.

The seed is deterministic. Ids are derived from fixed keys and every timestamp is an offset from one anchor,
**Monday 5 January 2026, 09:00 Asia/Kolkata** (`2026-01-05T03:30:00Z`). Nothing reads the clock, so a reset
reproduces exactly the same rows; an integration test compares a digest of every table before and after.

## Factory

| | |
|---|---|
| Name | Surat Diamond Works |
| Time zone | Asia/Kolkata |
| Currency | INR |
| Working week | Monday to Saturday, 09:00 to 18:00, break 13:00 to 14:00. Sunday closed. |
| Working time | 8 hours a day, 48 hours a week |

## Resource pools

A pool is a group of interchangeable units. Cost is per unit per hour, in INR.

| Kind | Pool | Category | Units | Hourly cost |
|---|---|---|---|---|
| Machine | Planning scanners | `PLANNING_SCANNER` | 3 | 220.00 |
| Machine | Laser marking units | `MARKING_LASER` | 2 | 180.00 |
| Machine | Laser saws | `CUTTING_LASER` | 4 | 300.00 |
| Machine | Bruting lathes | `BRUTING_LATHE` | 3 | 160.00 |
| Machine | Polishing wheels | `POLISHING_WHEEL` | 6 | 140.00 |
| Machine | Grading microscopes | `GRADING_MICROSCOPE` | 3 | 90.00 |
| Employee | Intake clerks | `INTAKE_CLERK` | 2 | 110.00 |
| Employee | Planners | `PLANNER` | 3 | 260.00 |
| Employee | Markers | `MARKER` | 2 | 190.00 |
| Employee | Cutters | `CUTTER` | 4 | 240.00 |
| Employee | Bruters | `BRUTER` | 3 | 210.00 |
| Employee | Polishers | `POLISHER` | 12 | 230.00 |
| Employee | Quality inspectors | `QUALITY_INSPECTOR` | 3 | 250.00 |
| Employee | Dispatch clerks | `DISPATCH_CLERK` | 2 | 120.00 |

One availability window is seeded: two of the six polishing wheels are out for the whole working day on
Wednesday 7 January 2026 (09:00 to 18:00 local), reason "Scheduled wheel maintenance (synthetic)".

## Workflow: Rough to polished

Rate is stones per hour **per processing slot**. Setup is per job. Yields are the fraction that leaves the
stage. Fixed cost is per job, in INR. "Slots" is how many jobs can run at once with every pool at full
capacity.

| # | Stage | Rate | Setup | Stone yield | Carat yield | Fixed cost | One slot needs | Slots |
|---|---|---|---|---|---|---|---|---|
| 1 | Rough Intake | 240 | 10 min | 100% | 100% | 50 | 1 intake clerk | 2 |
| 2 | Planning | 30 | 15 min | 100% | 100% | 120 | 1 planning scanner, 1 planner | 3 |
| 3 | Marking | 45 | 10 min | 100% | 100% | 60 | 1 laser marking unit, 1 marker | 2 |
| 4 | Cutting | 12 | 20 min | 98.5% | 62% | 200 | 1 laser saw, 1 cutter | 4 |
| 5 | Bruting | 18 | 15 min | 99.5% | 90% | 120 | 1 bruting lathe, 1 bruter | 3 |
| 6 | Polishing | 6 | 15 min | 99% | 82% | 150 | 1 polishing wheel, 2 polishers | 6 |
| 7 | Quality Inspection | 40 | 5 min | 97% | 100% | 80 | 1 grading microscope, 1 quality inspector | 3 |
| 8 | Dispatch | 300 | 10 min | 100% | 100% | 40 | 1 dispatch clerk | 2 |

Stage capacity in stones per hour is rate times slots: polishing manages 36, against 48 at cutting and 54 at
bruting. That gap is deliberate, so that later milestones have a constraint to find and scenarios such as
"add two polishing wheels" have something to change. Whether polishing is actually the bottleneck for a
given set of orders is a question for the simulation engine (Milestone 4), not something this table asserts.

## Not seeded yet

Production orders, batches and the operational event history arrive in Milestone 3. The preconfigured
scenarios arrive in Milestone 5.
