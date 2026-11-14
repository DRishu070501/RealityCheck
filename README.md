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
| 2. Factory configuration | Factory, workflow, stages, resource pools, requirements, demo seed | Not started |
| 3. Production operations | Orders, batches, append-only events, timeline | Not started |
| 4. Simulation engine | Discrete-event engine, calendar, yield, metrics, bottleneck detection | Not started |
| 5. Scenario branching | State reconstruction, scenarios, changes, comparison | Not started |
| 6. Frontend | The ten application screens | Shell and navigation only |
| 7. AI intelligence | Provider abstraction, explanations, Q&A | Not started |
| 8. Quality and deployment | End-to-end tests, polish, demo documentation | Not started |

What runs today: the backend starts, connects to PostgreSQL, applies migrations and serves health, system
info and API documentation; the frontend shows the application shell and a live system-status view. The
feature screens are placeholders that say which milestone delivers them. There is no factory data yet.

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
- [.kiro/steering/](.kiro/steering/): product, technology, architecture and simulation rules
- [.kiro/specs/](.kiro/specs/): requirements, design and tasks for each feature

## Repository layout

```
backend/     Spring Boot 3 (Java 21) modular monolith
frontend/    React + TypeScript + Vite single-page application
docs/        Architecture, setup and API documentation
.kiro/       Kiro steering documents, specifications, hooks and agents
```
