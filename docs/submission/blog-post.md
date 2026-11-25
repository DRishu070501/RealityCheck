# I built a time machine for a diamond factory, spec-first with Kiro

*Kiroween 2026, Frankenstein category. [Live demo](https://3-106-245-39.sslip.io) ·
[3-minute video](https://youtu.be/Rw4WrUri5G0) · [Code](https://github.com/DRishu070501/RealityCheck)*

In a diamond factory, a rough packet takes about **seventeen days** to reach dispatch. The people who work on
it spend hours on it, not weeks. Every manager has a theory about the fix: more people, another shift, a faster
machine. Nobody can test a theory on last week and see what would actually have happened.

So I built **RealityCheck**. It replays what really happened in a factory, lets you stop at any recorded
moment, change one decision, and simulates the other future beside the one that happened. In the
demonstration, halving the time packets sit parked finishes the work about 30% sooner. Hiring three more
inspectors changes nothing but the cost, because that stage was never waiting for people.

The hard part wasn't the simulation. The hard part was keeping every number honest. This post covers how Kiro
helped with that.

## A Frankenstein of five parts

RealityCheck is five parts that were never meant to share a body:

- **Skeleton:** an append-only history in PostgreSQL. A database trigger refuses any edit to the past.
- **Heart:** a discrete-event simulation engine in plain Java, with no framework and no randomness.
- **Blood:** patterns from one real factory's month of data, published only as rounded aggregates.
- **Voice:** an AI analyst (Claude) that explains results but is forbidden to calculate.
- **Face:** a React app where every number is tagged Actual, Simulated or AI interpretation.

Stitching those together is where projects like this usually go wrong. The AI makes up a percentage, or the
simulation quietly uses a random seed. Or a chart shows a number nobody can trace. I wanted rules that
couldn't drift, and that's what Kiro gave me.

## Steering: write the rules down once

Before any code, I wrote four steering documents in `.kiro/steering/`, about 560 lines in all. Kiro loads them
into every piece of work:

- **product.md:** every number shown comes from the engine or recorded data. AI explains and never calculates.
  Simulation never alters recorded history.
- **simulation-rules.md:** thirteen sections the engine must obey, covering determinism, event ordering, units,
  calendars and work in progress at the fork, plus the properties its tests must hold.
- **architecture.md:** module boundaries, enforced by ArchUnit tests.
- **tech.md:** the stack, and what not to add.

The surprise was how often these settled a design question before it became an argument. I needed per-stage
handling delays, and the obvious answer was a random number generator. But the steering says the engine is
deterministic: same input, same result, to the second. So the delay comes from a hash of the batch id
instead. That's realistic variation with no randomness, and every run reproduces exactly.

## Specs: eleven features, requirements to tasks

Every feature has a spec in `.kiro/specs/`:
- `requirements.md`, in WHEN/THE SYSTEM SHALL form;
- `design.md`;
- `tasks.md`.

There are eleven specs, 83 requirements and over 120 tasks. Each task names the requirements it satisfies
and the test that proves it.

Day to day, I worked task by task. I opened a spec's task list, asked Kiro to implement the next task against
its requirements, then checked the result against the acceptance criteria.

The best moment was spec 11, calibrating against a real factory. My first offline backtest showed that queues
on capacity couldn't explain how long packets sat in a department. Workers hold packets in parcels, so a packet
waits a day or two while the stage runs well below capacity. I updated the **design in the spec first**, adding
calibrated handling delays with a dated note explaining why. Only then did I change the engine. The spec
stayed the source of truth even when the data proved my first idea wrong.

When the build departed from a design, the spec was updated instead of left stale. Every `tasks.md` ends with
implementation notes recording what changed and why.

## Hooks: checks on save

Five hooks in `.kiro/hooks/` run on save, each scoped to a narrow file pattern:
- the touched backend module's unit tests;
- frontend lint and type-check;
- an API contract check against `docs/api.md`;
- a **simulation-engine review**, which checks engine changes against simulation-rules.md and decides whether
  the algorithm version must change;
- an architecture-docs sync when migrations or Compose change.

The engine review hook was my favourite. Changing the engine without bumping its version would make old
results look comparable with new ones when they're not. The hook catches that at the moment of saving.

## Custom agents and property-based tests

Five custom agents in `.kiro/agents/` act as specialists:
- simulation engineer;
- database designer;
- Java backend architect;
- React UI/UX;
- QA and testing.

Each is loaded with only its own steering and spec, so the UI agent never wanders into engine code.

Spec 07 requires the engine's correctness properties to be verified with property-based tests. There are
**29 jqwik properties** across 8 test classes. They cover:
- determinism;
- stone and carat conservation at every stage;
- stage order;
- capacity that never goes negative;
- termination.

My favourite is property 10: *adding capacity never increases completion time*. It holds only in the
single-stage case. With several stages sharing pools, adding capacity can occasionally make the schedule
longer, which is a known scheduling anomaly. The steering says so plainly, and the engine reports it rather
than hiding it.

## An AI that refuses to invent

The analyst is Claude, and the steering rule "AI never calculates" shaped all of it:
- Claude sees only evidence keys from stored simulation results.
- Every number it writes is checked against those results.
- If an answer contains a figure the evidence can't support, it's replaced by a built-in analyst, and the
  screen says so.

In the demo, Claude says the evidence does not isolate a cause, and refuses to invent one. That's the answer I
wanted.

## Honest accuracy

A simulator you can't check is just a story. RealityCheck has a backtest: it simulates past dates with no
changes and compares the result with what was recorded. On one real factory, imported privately, it's within
about a fifth on packets finished over a week. We publish exactly where it's wrong.

Our first real-data backtest actually looked better than that. The extract held only pieces that finished,
which flattered the result. We fixed the comparison window and published the less flattering numbers.

## What I'd tell someone starting with Kiro

**Write the steering first.** The rules you'd normally keep in your head ("never fabricate a number", "the
engine is deterministic") become context for every task. That's what turned "AI on top of a simulation" into
something I can trust.

Try it at **https://3-106-245-39.sslip.io**, and the code, including the whole `.kiro` directory, is at
**https://github.com/DRishu070501/RealityCheck**.

*#Kiro #Kiroween #KiroweenHackathon26*
