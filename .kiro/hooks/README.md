# Hooks

Five hooks, each triggered by saving files that match a narrow pattern and each running an agent prompt.
None of them runs the full build, the `*IT` integration tests or the end-to-end suite.

| File | Trigger pattern | What the agent does |
|---|---|---|
| `backend-module-tests.json` | `backend/src/{main,test}/java/com/realitycheck/**/*.java` | Runs the unit tests of the touched module only (`./mvnw -q test -Dtest=…`). |
| `api-contract-check.json` | `backend/src/main/java/com/realitycheck/*/api/**/*.java` | Checks the controller or DTO against the API conventions and the owning spec; keeps `docs/api.md` in step. |
| `frontend-lint-typecheck.json` | `frontend/src/**/*.{ts,tsx}` | ESLint on the saved file, project typecheck, and the adjacent test if there is one. |
| `simulation-engine-review.json` | `backend/src/main/java/com/realitycheck/simulation/engine/**/*.java` | Reviews the change and its tests against `simulation-rules.md`, runs the engine tests, decides whether `ALGORITHM_VERSION` must be bumped. |
| `architecture-docs-sync.json` | Flyway migrations, `backend/pom.xml`, `docker-compose.yml`, `*ArchitectureTest.java` | Brings `docs/architecture.md`, spec data models and setup docs in line; never edits steering without asking. |

## Format note

Kiro's documentation states that standalone hooks live in `.kiro/hooks/*.json` and that a hook has a name, a
trigger, a matcher and an action (agent prompt or shell command). The exact key names and trigger identifier
used in these files (`trigger: "fileSave"`, `matcher`, `action.type: "agentPrompt"`) could not be confirmed
from the published pages. If Kiro does not list these hooks in the Agent Hooks panel, recreate each one there
("File Save" trigger, the pattern from the table, "Agent Prompt" action) and paste the `prompt` text from the
JSON file; then replace the file here with the one Kiro writes.
