---
inclusion: always
---

# RealityCheck — Product

**Tagline:** See the future before you change the present.

RealityCheck is an AI-assisted digital twin for business operations. It recreates a factory's workflow,
resources and production history, then simulates alternative decisions so their consequences can be compared
before anything changes on the real shop floor. The first implementation targets diamond manufacturing.

## Vision

Operational decisions (buy machines, hire, add a shift, accept more orders) are usually made from experience
and historical reports. RealityCheck lets a manager branch from any point in the factory's recorded history,
change the conditions, and see the simulated outcome next to the baseline.

The defining feature is the **operational time machine**: pick a historical timestamp, reconstruct the factory
state at that moment, fork a scenario, and compare futures. Everything else supports that.

## Target customers

Small and mid-sized diamond manufacturers. Users: factory owners, production managers, operations managers,
manufacturing supervisors, production planners.

## Problems addressed

- Production bottlenecks that are noticed only after orders are late.
- Under-used machinery and badly balanced staffing.
- No reliable way to estimate the effect of a change before paying for it.

## Product objectives

1. Model one complete factory: workflow, resources, orders, event history.
2. Produce deterministic, reproducible simulated outcomes for a scenario.
3. Explain *why* a stage is a bottleneck, with visible evidence.
4. Compare a baseline with alternatives that share the same starting state.
5. Use AI to explain and recommend, never to calculate.

## Non-negotiable product rules

- **Every number shown comes from the simulation engine or recorded data.** AI never produces or alters a
  numeric result.
- Three kinds of value are always labelled distinctly in the UI and API: **Actual** (recorded history),
  **Simulated** (engine output), **AI interpretation** (generated text).
- No guaranteed savings or improvements are claimed. Simulated values are estimates under stated assumptions.
- All demo data is synthetic and labelled as such. "Surat Diamond Works" is a fictional business; its rates,
  capacities and costs are not industry benchmarks.

## MVP scope

- One factory, one sequential eight-stage workflow: Rough Intake, Planning, Marking, Cutting, Bruting,
  Polishing, Quality Inspection, Dispatch.
- Resource pools of two kinds: machines and employees.
- Production orders made of batches (stones and carats tracked separately).
- Append-only operational event history and a browsable timeline.
- Scenario branching from a historical timestamp with six change types (see architecture.md).
- Deterministic discrete-event simulation, metrics, bottleneck evidence.
- Baseline-versus-alternative comparison.
- AI explanation, recommendations and Q&A grounded in simulation output, with an offline fallback.
- Six preconfigured demo scenarios and a resettable seed.

## Non-goals for the MVP

- Authentication, users, workspaces, multi-tenancy (the schema keeps `factory_id` on every aggregate so this
  can be added without reshaping tables).
- Parallel or branching workflows, rework loops, batch splitting and merging.
- Stochastic simulation (processing-time variance, random breakdowns). A `random_seed` column exists but is
  unused.
- Preemptive scheduling, per-individual employee rosters, overtime rules.
- Integration with real ERP/MES systems. History is entered through the API or the seed.
- Optimisation ("find the best plan"). The product evaluates scenarios a person proposes.
