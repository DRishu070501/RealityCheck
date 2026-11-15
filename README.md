# RealityCheck

**See the future before you change the present.**

RealityCheck is a digital twin for business operations. It models a factory's workflow, resources and
production history, lets you branch from any point in that history, change the conditions, and compare the
simulated outcome with the baseline. The first implementation targets diamond manufacturing.

Two principles hold throughout:

- **Numbers come from the simulation engine or from recorded history.** The engine is deterministic: the
  same input and algorithm version always give the same result. AI explains results; it never calculates them.
- **Demo data is synthetic.** "Surat Diamond Works" is a fictional factory. Its rates, capacities and costs
  are not industry benchmarks, and simulated outcomes are estimates, not guarantees.

## Status

| Milestone | Scope | State |
|---|---|---|
| 1. Foundation | Backend, frontend shell, PostgreSQL, Flyway, OpenAPI, health checks, Docker Compose, Kiro steering and specs | **Done** |
| 2. Factory configuration | Factory, workflow, stages, resource pools, requirements, demo seed | **Done** |
| 3. Production operations | Orders, batches, append-only events, timeline | **Done** |
| 4. Simulation engine | Discrete-event engine, calendar, yield, metrics, bottleneck detection | **Done** |
| 5. Scenario branching | Scenarios, changes, stored background runs, comparison | **Done** |
| 6. Frontend | The ten application screens | **Done** |
| 7. AI intelligence | Provider abstraction, evidence-checked explanations, Q&A, AI Analyst screen | **Done** |
| 8. Quality and deployment | End-to-end tests, polish, demo documentation | Not started |

What runs today: a seeded demonstration factory, Surat Diamond Works, with its working calendar, fourteen
resource pools, an eight-stage workflow, and two to three weeks of production history ending now. You can
browse orders and their batches, follow the operational timeline, inspect the factory as it stood at any
past moment, see current queues and units in use, edit pools, record downtime and reset the demonstration
data. The deterministic simulation engine is built and can be run on the factory as recorded from any
instant through `GET /api/factories/{id}/baseline-simulation` (see Swagger UI); it predicts completion, waiting,
utilization, cost and delivery, and names the bottleneck with its evidence. In **Scenario Lab** you can open
one of six ready-made what-ifs or create your own (from scratch, by branching, or from any moment on the
Timeline), add changes, check them, and run the simulation; **Simulation Results** shows a run on its own or
side by side with the baseline, with charts, bottleneck evidence and order-by-order delivery. Overview shows
what the baseline predicts if nothing changes. **AI Analyst** explains a chosen run (on its own or against a
baseline): reports on the bottleneck, delays, recommendations and an executive summary, and answers to your
questions, each with the simulated evidence behind it. Every number in an answer is checked against the stored
results before it is shown; answers that fail the check are replaced by the built-in analyst's. It uses Claude
when `AI_API_KEY` is set and the built-in template analyst otherwise (see [docs/ai.md](docs/ai.md)).

## Run it with Docker

Requires Docker Desktop (or Docker Engine with Compose v2).

```bash
cp .env.example .env        # PowerShell: Copy-Item .env.example .env
docker compose up --build
```

| What | URL |
|---|---|
| Application | http://localhost:3000 |
| API documentation (Swagger UI) | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| System info | http://localhost:8080/api/system/info |

Ports are set in `.env`. The database is published on host port 15432, not 5432, so it does not collide with
a locally installed PostgreSQL. Stop with `docker compose down`; add `-v` to delete the database volume.

## Develop locally

See [docs/setup.md](docs/setup.md) for prerequisites, running the backend and frontend outside Docker, and
the test commands.

```bash
docker compose up -d db                 # database only
cd backend && ./mvnw spring-boot:run    # http://localhost:8080  (JDK 21+)
cd frontend && npm install && npm run dev   # http://localhost:5173
```

## Documentation

- [docs/architecture.md](docs/architecture.md): modules, data flow, key decisions and their trade-offs
- [docs/setup.md](docs/setup.md): prerequisites, configuration, commands, troubleshooting
- [docs/api.md](docs/api.md): API conventions and the endpoints implemented so far
- [docs/demo-data.md](docs/demo-data.md): the synthetic demonstration factory and how to reset it
- [.kiro/steering/](.kiro/steering/): product, technology, architecture and simulation rules
- [.kiro/specs/](.kiro/specs/): requirements, design and tasks for each feature

## Repository layout

```
backend/     Spring Boot 3 (Java 21) modular monolith
frontend/    React + TypeScript + Vite single-page application
docs/        Architecture, setup and API documentation
.kiro/       Kiro steering documents, specifications, hooks and agents
```
