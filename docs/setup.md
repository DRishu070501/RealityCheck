# Setup

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Docker with Compose v2 | recent | Running the stack; backend integration tests |
| JDK | 21 or newer | Building and running the backend outside Docker |
| Node.js | 22 or newer | Building and running the frontend outside Docker |

Maven does not need to be installed: `backend/mvnw` downloads the right version on first use.

The backend is compiled for Java 21 (`--release 21`). Any JDK from 21 upward can build it; the build stops
with a clear message on an older one. If `java -version` reports something older, point `JAVA_HOME` at a
newer JDK for the session:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-22"          # Git Bash
$env:JAVA_HOME = "C:\Program Files\Java\jdk-22"          # PowerShell
```

## Configuration

All configuration is environment variables. `.env.example` lists every one with a comment; copy it to `.env`
(git-ignored) for Docker Compose.

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `realitycheck` | Database name and credentials. Compose refuses to start without a password. |
| `DB_HOST_PORT` | `15432` | Host port for PostgreSQL (loopback only) |
| `BACKEND_HOST_PORT` | `8080` | Host port for the API (loopback only) |
| `FRONTEND_HOST_PORT` | `3000` | Host port for the web application |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Origins allowed to call the API from a browser. Keep the Docker frontend's origin in the list: browsers send `Origin` on every write, even through its proxy, and a missing entry makes every save fail with 403 |
| `API_DOCS_ENABLED` | `true` | Serve Swagger UI and the OpenAPI document |
| `REALITYCHECK_DEMO_SEED` | `true` in `.env.example`, `false` when unset | Seed the demonstration factory at start-up if the database has no factory |
| `REALITYCHECK_DEMO_RESET_ENABLED` | `true` in `.env.example`, `false` when unset | Allow `POST /api/demo/reset`, which erases all data. See [demo-data.md](demo-data.md). |
| `AI_PROVIDER`, `AI_API_KEY` | `template`, empty | `anthropic` plus a key uses Claude for the AI Analyst; otherwise the built-in template analyst answers. The key stays in the backend container. Other `AI_*` settings: [ai.md](ai.md). |

When the backend runs outside Docker it reads `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`, defaulting to
`jdbc:postgresql://localhost:15432/realitycheck` with user and password `realitycheck`, which matches
`docker compose up -d db` with the example `.env`. The `prod` profile used inside Docker has no credential
defaults.

## Run everything in Docker

```bash
cp .env.example .env
docker compose up --build
```

Start-up order is enforced by health checks: database, then backend (which applies Flyway migrations), then
frontend. Open http://localhost:3000.

## Run for development

```bash
docker compose up -d db

cd backend
./mvnw spring-boot:run            # API on http://localhost:8080

cd frontend
npm install
npm run dev                       # UI on http://localhost:5173, proxies /api to :8080
```

## Tests and checks

Backend, from `backend/`:

| Command | Runs | Needs Docker |
|---|---|---|
| `./mvnw test` | Unit tests (`*Test`), property tests (`*Properties`), architecture rules | No |
| `./mvnw verify` | The above plus integration tests (`*IT`) against PostgreSQL in Testcontainers | Yes |
| `./mvnw test -Dtest=ClassName` | One class | No |

Frontend, from `frontend/`:

| Command | Runs |
|---|---|
| `npm run lint` | ESLint |
| `npm run typecheck` | TypeScript, no emit |
| `npm test` | Vitest, single run |
| `npm run build` | Production build into `dist/` |
| `npm run e2e` | Playwright end-to-end suite against a running stack (below) |

### End-to-end tests

`frontend/e2e/` holds two Playwright suites that run against the application as deployed, not against
mocks:

- `journey.spec.ts`: the twelve steps of the definition of success through the UI (see
  [demo.md](demo.md)): open the factory, its stages and resources, browse history, inspect a past moment,
  branch a scenario from it, add a capacity change, run it, create and run the baseline for that moment,
  compare, read the bottleneck and the recorded outcome, and get the AI analyst's recommendations with their
  evidence. Each run creates its own scenario, so it can be repeated. It passes with Claude or with the
  built-in analyst.
- `accessibility.spec.ts`: every screen at 375, 768, 1024 and 1440 px: no serious or critical axe
  violations (WCAG 2.1 A and AA, colour contrast included) and no sideways page scroll; plus a keyboard check
  of the navigation and its visible focus.

```bash
docker compose up --build -d      # from the repository root; wait until the frontend is healthy
cd frontend
npx playwright install chromium   # once
npm run e2e                       # about three minutes
```

`E2E_BASE_URL` points it elsewhere (default `http://localhost:3000`), for example
`E2E_BASE_URL=http://localhost:5173 npm run e2e` against `npm run dev`. A failure leaves a screenshot and a
trace in `frontend/test-results/`; `npx playwright show-report` opens the HTML report. With Claude
configured, one journey run costs about half a US cent.

## Database migrations

Migrations live in `backend/src/main/resources/db/migration` as `V<n>__<description>.sql` and run
automatically when the backend starts. Hibernate is set to `validate`: it never changes the schema. Add a
new file for every change; do not edit one that has been applied.

## Troubleshooting

**`release version 21 not supported` or the enforcer message about JDK 21.** `JAVA_HOME` points at an older
JDK. See Prerequisites.

**Integration tests fail with "Could not find a valid Docker environment".** Docker is not running. Start
Docker Desktop and retry; `./mvnw test` works without it.

**`port is already allocated`.** Something else is using a host port. Change `DB_HOST_PORT`,
`BACKEND_HOST_PORT` or `FRONTEND_HOST_PORT` in `.env`.

**Backend exits with a connection error outside Docker.** The database container is not running or
`DB_HOST_PORT` was changed; set `DB_URL` to match.

**Start again from an empty database.** `docker compose down -v`.

**Restore the demonstration data.** Settings, then Reset demo data (needs
`REALITYCHECK_DEMO_RESET_ENABLED=true`), or `curl -X POST http://localhost:8080/api/demo/reset`.

**No factory appears when running the backend with `./mvnw spring-boot:run`.** The seed is off unless
`REALITYCHECK_DEMO_SEED=true` is in the environment of that process.
