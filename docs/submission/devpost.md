# Devpost submission text

Paste each section into the matching Devpost field. Fill the bracketed links before submitting.

- **Project name:** RealityCheck
- **Tagline (one line):** A time machine for the factory floor: stop at any recorded moment, change one
  decision, and watch the other future play out beside the one that happened.
- **Category:** Frankenstein
- **Links:** public app [https://…], video [https://…], repository https://github.com/DRishu070501/RealityCheck
- **Screenshots:** `docs/screenshots/01-overview.png` to `09-halloween-ghost-futures.png`, in order.

---

## Inspiration

In a diamond factory, a rough packet takes about seventeen days to reach dispatch. The people who work on it
spend hours on it, not weeks. Managers argue about the fix: more people? another shift? Nobody can try a
decision on last week and see what would have happened. We wanted to make that possible, honestly, with
numbers that can be traced to their source.

## What it does

RealityCheck replays what really happened in a factory, lets you stop at any recorded moment, change a
decision, and simulates the other future beside the one that happened.

- **Recorded history** of every packet movement, append-only. The factory can be rebuilt at any past instant.
- **Scenarios** fork from a recorded moment and change one thing: capacity, a shift, demand, how long packets
  are parked, how slow a job worker is.
- **A deterministic simulation engine** runs the factory forward from the fork under each decision. Same input,
  same result, to the second.
- **Comparison with ghost futures:** every order's completion beside its ghost under the alternative, and
  diamonds for what history really recorded where it already knows.
- **Haunted stages** and **Where Time Goes:** where packets lose time with nobody working on them, measured from
  recorded events.
- **An AI analyst** (Claude) explains results and answers questions using only stored evidence. Every number
  it writes is checked against the simulation. An answer that fails is replaced by a built-in analyst, and the
  screen says so.
- **Accuracy:** a backtest simulates past dates with no changes and compares with what was recorded, and the
  errors are shown as measured.
- **Twelve screens**, every figure tagged Actual, Simulated or AI interpretation, with a table behind every
  chart. Plus a Halloween theme with the same contrast guarantees.

In the demonstration, halving parking time finishes the work about 30% sooner and lifts on-time delivery from
57% to 62%. Three more inspectors change nothing but cost (+9%), because that stage isn't waiting for
capacity.

## How we built it: the Frankenstein

Five parts that were never meant to share a body, stitched by contracts rather than shared code:

| Part | Technology | Never allowed to |
|---|---|---|
| Skeleton: append-only history | PostgreSQL 17, a trigger that refuses edits, Flyway | change the past |
| Heart: discrete-event engine | plain Java 21, no framework, no randomness | guess |
| Blood: a real factory's patterns | Python and pandas, run on the owner's machine against a read-only copy | publish a name or a raw row |
| Voice: AI analyst | Claude (Haiku 5.5) through the Anthropic Java SDK, structured output | calculate a number |
| Face: the app | React 19, TypeScript, Tailwind, TanStack Query | show a number without its source |

The engine sees only a frozen, hashed snapshot. The analyst sees only evidence keys, and its numbers are
validated against them. The real factory reaches the repository only as rounded aggregates, which a script
refuses to publish if any real name appears. Spring Boot 3.5 ties it together, and Docker Compose runs it with
one command.

## How we used Kiro

The project was built spec-first from the `.kiro` directory:

- **Steering**, four documents of non-negotiable rules: every number comes from the engine or recorded data;
  AI never calculates; the engine is deterministic; module boundaries are enforced by ArchUnit tests.
- **Eleven specs**, each with requirements in WHEN/THE SYSTEM SHALL form, a design and a task list: 83
  requirements and over 120 tasks, each task tied to its requirements and to the test that proves it. When the
  build departed from a design, the spec was updated with an implementation note saying why.
- **Hooks** run checks on save (module tests, lint and type-check, API contract, simulation-engine review,
  architecture docs).
- **Agents** are scoped to the engine, the database, the backend, the UI and testing, each loaded with only
  its steering and spec.

The steering rules made real decisions. The engine gained calibrated delays without gaining randomness (a hash
of the batch id instead), because determinism is a rule. The backtest reads history through the scenario
module because simulation may not read production directly. Details are in `docs/kiro.md`.

[Owner: add two or three sentences in your own words about working in the Kiro IDE: how you used spec
generation, chat or hooks day to day, and what surprised you.]

## Challenges we ran into

- **Real data, kept private.** We calibrated on one real factory's month (about 30,000 pieces), using
  read-only access and local extracts only. Every published file is checked against a local list of real
  names. We even found two names that had slipped into a commit and rewrote the history before the first
  push.
- **Queues alone don't explain a factory.** Workers hold packets in parcels, so a packet sits in a department
  for a day or two while the stage runs well below capacity. Capacity queuing couldn't reproduce that, so the
  engine models a calibrated handling delay per stage, and the docs explain what that means: more people only
  help where a stage is actually waiting for capacity.
- **Honest accuracy.** Our first real-data backtest looked better than it was, because the extract held only
  pieces that finished. We changed the comparison window and published the less flattering numbers.

## Accomplishments that we're proud of

- An AI analyst that cannot fabricate a number: every figure is checked, and it says when the evidence
  doesn't support a cause.
- A real factory backtest, run privately and published as rounded errors: within about a fifth on packets
  finished over a week, and wrong in ways we document.
- 345 unit, 120 integration, 251 frontend and 74 browser tests, including accessibility checks in both
  themes. A fresh clone starts with one command.

## What we learned

The biggest lever in the real factory wasn't people or machines but time spent waiting with nobody working:
parking was about two fifths of lead time. And writing the rules down first is what kept "AI on top of a
simulation" honest.

## What's next

- Model packet splitting directly instead of as weight loss.
- Calibrate a second factory, whose data is already extracted.
- Fix the main source of error the backtests found: work in progress at the fork restarts its handling delay.

## Built with

java, spring-boot, postgresql, flyway, react, typescript, tailwindcss, vite, python, pandas, docker, caddy,
claude, anthropic-sdk, playwright, kiro
