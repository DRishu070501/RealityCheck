# Demonstration guide

A walkthrough for someone seeing RealityCheck for the first time: start it, then follow the operational
time machine from a recorded moment to a simulated alternative, its comparison with what would have
happened anyway, and the AI analyst's explanation. About fifteen minutes. No developer steps are needed.

**Everything you will see is synthetic.** Surat Diamond Works is a fictional factory. Its history is
generated when the application first starts, so dates, order numbers and exact figures differ from one
installation to another. Simulated figures are estimates under the engine's rules, not predictions you can
hold anyone to.

## 1. Start it

Requires Docker Desktop (or Docker Engine with Compose v2) and free ports 3000, 8080 and 15432.

```bash
cp .env.example .env        # PowerShell: Copy-Item .env.example .env
docker compose up --build
```

The first build downloads dependencies and takes a few minutes; later starts take under a minute. When the
log shows the frontend as healthy, open **http://localhost:3000**. The demonstration factory, two to three
weeks of its history and six ready-made scenarios are created on first start.

**AI analyst (optional).** Without a key, a built-in analyst answers from fixed rules and the screens say
so. To use Claude, put `AI_PROVIDER=anthropic` and `AI_API_KEY=<your key>` in `.env` and run
`docker compose up -d backend`. See [ai.md](ai.md) for what it costs (well under one US cent per answer).

## 2. The walkthrough

Each step names the screen in the left-hand navigation. Tags on every panel say where its numbers come
from: **Configuration** (what was set up), **Actual** (recorded history), **Simulated** (the engine) or
**AI interpretation**.

| Step | Screen | Do this | Look for |
|---|---|---|---|
| 1. Open the factory | Overview | Nothing; it opens here | Surat Diamond Works with 8 stages, 14 resource pools, what recorded history says about today, and "If nothing changes" (press **Run baseline simulation** if it has not run yet) |
| 2. Eight production stages | Workflow | Select a stage | Rough Intake to Dispatch, each with its rate, setup, yield, cost and required resources; current queues from history |
| 3. Machines and people | Resources | Switch between Machines and Employees | Capacity, units in use now, hourly cost, which stages need each pool |
| 4. Browse history | Operational Timeline | Set **To day** a few days back; open an event | Every recorded event, including a correction that keeps the original value visible |
| 5. Pick a moment | Operational Timeline | **Inspect factory at this moment** | The factory rebuilt as it stood then: orders by status, queues per stage, pools, batches waiting |
| 6. Branch from it | Operational Timeline | **Create scenario from this point**, give it a name | A new scenario in Scenario Lab, starting from that moment |
| 7. Change capacity | Scenario Lab | **Add change**: Resource capacity, Polishing wheels, +2 (or Resource unavailable for people) | **Check before running** shows the configuration before and after; nothing is simulated yet |
| 8. Simulate | Scenario Lab | **Run simulation** | Completes in about a second. The same scenario always gives the same numbers |
| 9. Compare | Scenario Lab, then Simulation Results | **Create and run baseline** (the factory as recorded from the same moment, no changes), then **Compare with baseline** | Headline metrics side by side with differences and the direction of each; charts each with a table; per stage, per pool and per order. Below them, **What actually happened after the fork**: recorded history for the same orders, beside the simulation, never subtracted from it |
| 10. Bottleneck | Simulation Results | Read the **Bottleneck** card | Which stage limits the factory in each run and the scores behind it, decided by fixed rules, not AI |
| 11. Ask AI | Simulation Results, then AI Analyst | **Explain with AI Analyst**, then **Recommend next steps**, or ask your own question | An answer tagged AI interpretation; who answered (Claude or the built-in analyst) |
| 12. Recommendations backed by metrics | AI Analyst | Open **Evidence** beside a recommendation | The exact simulated figures the recommendation relies on, each from a named run. Every number in the answer was checked against them before it was shown |

### A quicker comparison

Scenario Lab already holds six scenarios forked five working days before the factory was seeded:
"Current factory operations" (the baseline) and five alternatives such as **Add two polishing machines** and
**Introduce an additional shift**. Open one, press **Run simulation**, then **Run baseline** if offered,
and **Compare with baseline**. Things worth noticing (they depend on the seeding day, so treat them as
examples):

- **Add two polishing machines** changes little: each wheel needs two polishers and there are only twelve,
  so the extra wheels stand idle. The bottleneck card shows why.
- **Introduce an additional shift** finishes work much sooner and cuts delayed orders, for a small increase
  in total cost; compare cost per stone as well as total cost.
- **Reduce cutting capacity by 30%** moves the bottleneck from polishing to cutting.

### Questions to try in the AI Analyst

- "Why is polishing the bottleneck?"
- "Which orders are late, and why?"
- "What if we hired two more polishers?" The analyst says this has not been simulated and suggests creating
  the scenario instead of guessing.

## 3. Starting again

**Settings, Reset demo data** erases everything (including scenarios you created) and seeds the factory
again with history up to today. You type RESET to confirm. The same is available as
`POST /api/demo/reset`. To start from an empty database instead: `docker compose down -v`, then
`docker compose up --build`.

## 4. If something is off

| Symptom | Cause and fix |
|---|---|
| `port is already allocated` | Another program uses 3000, 8080 or 15432. Change `FRONTEND_HOST_PORT`, `BACKEND_HOST_PORT` or `DB_HOST_PORT` in `.env`. If you change the frontend port, add its address to `CORS_ALLOWED_ORIGINS` too, or every save fails with 403 |
| The AI Analyst says the built-in analyst answered | No key is configured, the key was refused, or Claude's answer did not pass the evidence check. The notice says which. Everything else works the same |
| Many orders show as late | The demonstration does not move on by itself; days after seeding, the work in progress is stale. Reset the demo data |
| A page shows "Backend unreachable" | The backend is still starting (it waits for the database); wait a few seconds and press Retry |

## 5. Checking it automatically

With the stack running, from `frontend/`:

```bash
npm install
npx playwright install chromium
npm run e2e
```

This drives a real browser through the twelve steps above (it creates its own scenario each time) and checks
every screen for accessibility problems at four screen widths. See [setup.md](setup.md#end-to-end-tests).
