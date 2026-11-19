# How Kiro shaped RealityCheck

RealityCheck was built spec-first. Everything in [.kiro/](../.kiro/) was written before or alongside the code
it governs, and the code was built task by task against it. This page describes those artifacts and how they
were used. Every count here can be checked in the repository.

## Steering: the rules that do not change

Four steering documents (about 560 lines) are loaded as context for every piece of work:

| File | What it fixes |
|---|---|
| [product.md](../.kiro/steering/product.md) | Non-negotiable product rules: every number shown comes from the simulation engine or recorded data; AI explains and never calculates; recorded history is never altered by a simulation; demo data is labelled. |
| [tech.md](../.kiro/steering/tech.md) | The stack and its versions, and what not to add. |
| [architecture.md](../.kiro/steering/architecture.md) | Module boundaries, enforced by ArchUnit tests. For example, the engine is pure Java, and simulation may read the scenario module but not production directly. |
| [simulation-rules.md](../.kiro/steering/simulation-rules.md) | Thirteen sections the engine must obey: determinism, time, event ordering, units, allocation, calendar, yield, work in progress at the fork, metrics, bottleneck rules, and the properties its tests must hold. |

These rules decided real design questions. The backtest reads recorded progress through the scenario module
because architecture.md forbids simulation reading production. The engine gained no random numbers for
calibrated delays, using a hash of the batch id instead, because simulation-rules.md requires determinism.
The AI answer is checked number by number against the evidence because product.md says AI never calculates.

## Specs: eleven features, requirements to tasks

Each feature in [.kiro/specs/](../.kiro/specs/) has `requirements.md` (user stories with WHEN/THE SYSTEM SHALL
acceptance criteria), `design.md` and `tasks.md`.

| Spec | Requirements | Tasks |
|---|---|---|
| 01 Factory setup | 5 | 8 |
| 02 Production workflow | 5 | 6 |
| 03 Resource management | 5 | 9 |
| 04 Operational event history | 7 | 11 |
| 05 Historical timeline | 6 | 8 |
| 06 Scenario branching | 7 | 12 |
| 07 Simulation engine | 16 | 22 |
| 08 Scenario comparison | 8 | 9 |
| 09 AI insights | 7 | 10 |
| 10 Dashboard frontend | 10 | 16 |
| 11 Real-factory calibration and Kiroween release | 7 | 16 |

Every task names the requirements it satisfies and the test that proves it. When the build departed from the
design, the spec was updated rather than left stale: each `tasks.md` ends with **implementation notes** that
record the difference and the reason. For example, spec 04 merged its projector and validator into one class,
spec 11 moved the backtest migration to V12, and spec 11 replaced cron plus an admin port with an in-process
schedule. Tasks deliberately left open say why (spec 10, tasks 2 and 5).

Spec 11 shows the loop best. It was written after the core product worked, to calibrate it on a real factory,
and it was re-planned as the data answered questions. Task 4's offline backtest showed that queuing on
capacity could not reproduce how long pieces stay in a department. The design was updated to add calibrated
handling delays (recorded in design.md with its date) before any engine code changed.

## Hooks: checks on save

Five hooks in [.kiro/hooks/](../.kiro/hooks/), each scoped to a narrow file pattern
([README](../.kiro/hooks/README.md)):

- **Backend module tests:** run the touched module's unit tests.
- **API contract check:** keep controllers in line with the API conventions and `docs/api.md`.
- **Frontend lint and type-check:** run on the saved file.
- **Simulation engine review:** check engine changes against simulation-rules.md and decide whether
  `ALGORITHM_VERSION` must change.
- **Architecture docs sync:** run when migrations, the build or Compose change.

## Agents: specialists with the right context

Five agent definitions in [.kiro/agents/](../.kiro/agents/) give a specialist the steering and spec files for
its area and nothing else:

- simulation engineer
- database designer
- Java backend architect
- React UI/UX
- QA and testing

For example, the simulation engineer gets the four steering documents and spec 07.

## What this bought

- **Numbers you can trace.** Because the rules were written down first, the product never shows a number
  without a source. AI answers that cite an unknown figure are rejected and replaced by the built-in analyst.
- **Change without drift.** Eleven specs and about 120 completed tasks, with the specs still describing the
  code that exists.
- **Honest results.** The specs required backtests against recorded history. Their errors are published as
  measured in [accuracy.md](accuracy.md), not tuned away.
