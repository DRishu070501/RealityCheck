# 10 Dashboard and Frontend — Requirements

## Introduction

The frontend is a React single-page application styled as a dark industrial operations console. It is a
client of the REST API defined in specs 01-09 and holds no business logic: every figure it shows is taken
from an API response, and every figure is labelled as **Actual**, **Simulated** or **AI interpretation**.

The Milestone 1 foundation (Vite project, Tailwind tokens, app shell, API client, health display) exists
before this spec starts. This spec covers the ten navigation destinations and the end-to-end journey.

## Requirements

### Requirement 1: Application shell and navigation

**User Story:** As a user, I want a persistent sidebar and consistent page structure, so that I always know
where I am.

#### Acceptance Criteria

1. WHEN the application loads THE SYSTEM SHALL show a sidebar with, in order: Overview, Factory, Workflow, Resources, Production Orders, Operational Timeline, Scenario Lab, Simulation Results, AI Analyst, Settings; the current destination is highlighted and each has its own URL.
2. WHEN the viewport is narrower than 1024 px THE SYSTEM SHALL collapse the sidebar to icons, and below 768 px to a menu button; no page scrolls horizontally at 375 px.
3. WHEN the active factory has `demo: true` THE SYSTEM SHALL show a persistent "Demo data — synthetic" badge in the header.
4. WHEN an unknown URL is opened THE SYSTEM SHALL show a not-found page with a link to Overview.
5. WHEN the backend is unreachable THE SYSTEM SHALL show a banner stating that and a retry control; pages show their error state, not stale or placeholder numbers.

### Requirement 2: Data provenance and states

**User Story:** As a factory owner, I want to know whether a number is recorded, simulated or AI-written, so
that I never mistake an estimate for a fact.

#### Acceptance Criteria

1. WHEN a metric from recorded data is shown THE SYSTEM SHALL tag its panel "Actual"; from a simulation run, "Simulated" with the scenario name; AI text, "AI interpretation" with the provider.
2. WHEN a view is loading THE SYSTEM SHALL show skeleton placeholders with the final layout's dimensions.
3. WHEN a query returns no data THE SYSTEM SHALL show an empty state that says what is missing and offers the action that creates it.
4. WHEN a query fails THE SYSTEM SHALL show the problem `title` and `detail` from the API and a retry button, and never a stack trace.
5. WHEN a number is displayed THE SYSTEM SHALL show its unit, and SHALL format durations as hours or days, money with the factory currency, and stones as whole numbers with the exact value available on hover.
6. WHEN the API returns a value as `null` THE SYSTEM SHALL show "—" with an explanatory tooltip, not zero.

### Requirement 3: Overview

**User Story:** As a factory owner, I want one page that shows the state of the factory now, so that I can
see problems at a glance.

#### Acceptance Criteria

1. WHEN Overview loads THE SYSTEM SHALL show from `GET /api/factories/{id}/overview`: active, pending, completed and delayed order counts, per-stage queue and in-progress counts, a 14-day output trend chart, and the ten most recent events.
2. WHEN a completed baseline simulation exists THE SYSTEM SHALL show machine utilization, employee utilization, throughput and the bottleneck stage from that run in a separate panel tagged "Simulated" with a link to the run.
3. WHEN no completed simulation exists THE SYSTEM SHALL show an empty state in that panel with a "Run baseline simulation" action.

### Requirement 4: Factory, Workflow, Resources, Orders

**User Story:** As a production manager, I want to inspect the factory's configuration and orders, so that I
can check the twin matches reality.

#### Acceptance Criteria

1. WHEN Factory loads THE SYSTEM SHALL show name, time zone, currency and the weekly calendar as a seven-day grid with shifts and breaks.
2. WHEN Workflow loads THE SYSTEM SHALL draw the stages as a left-to-right sequence of connected nodes in sequence order, each showing rate, setup, yields, required pools with units per slot, maximum slots, and current queued and in-progress counts.
3. WHEN a stage node is selected THE SYSTEM SHALL open a detail panel with its full parameters and, if a simulation run is selected, that run's stage metrics tagged "Simulated".
4. WHEN Resources loads THE SYSTEM SHALL list machine pools and employee pools separately with capacity, hourly cost, status, units in use and the stages that require each pool.
5. WHEN a pool is opened THE SYSTEM SHALL show its availability windows and capacity timeline and allow editing capacity, cost and status with validation and a conflict message on HTTP 409.
6. WHEN Production Orders loads THE SYSTEM SHALL show a paginated table (order number, priority, stones, rough carats, status, due date, delayed flag) with a status filter; stones and carats are in separate columns.
7. WHEN an order is opened THE SYSTEM SHALL show its batches with current stage and its event history.

### Requirement 5: Operational Timeline

**User Story:** As a user of the time machine, I want to browse history and pick the moment to branch from.

#### Acceptance Criteria

1. WHEN Timeline loads THE SYSTEM SHALL show an activity histogram from `GET /api/timeline/summary` and a chronological event list from `GET /api/timeline` with filters for stage, order and event type.
2. WHEN an event is selected THE SYSTEM SHALL show its details, including original and corrected values when corrected.
3. WHEN the user selects an instant (by clicking the histogram, an event, or entering a date and time) THE SYSTEM SHALL fetch and show the reconstructed factory state at that instant.
4. WHEN the user chooses "Create scenario from this point" THE SYSTEM SHALL open a dialog asking for a name, create the scenario with that `forkAt`, and navigate to it in Scenario Lab.
5. WHEN the selected instant is outside the recorded range THE SYSTEM SHALL disable the action and say why.

### Requirement 6: Scenario Lab

**User Story:** As an operations manager, I want to build a scenario, run it and see progress, so that I can
test a decision in minutes.

#### Acceptance Criteria

1. WHEN Scenario Lab loads THE SYSTEM SHALL list scenarios with fork time, change count and latest run status, and allow creating a scenario from scratch or by branching an existing one.
2. WHEN a scenario is open THE SYSTEM SHALL allow renaming it and editing its description inline.
3. WHEN the user adds a change THE SYSTEM SHALL offer a form per change type (resource capacity, resource unavailable, resource downtime, demand volume, processing rate, working schedule) with pools and stages chosen from lists, validated with Zod before submission.
4. WHEN a form value is impossible (negative capacity, percent below -100, end before start, empty schedule) THE SYSTEM SHALL block submission and show the reason beside the field.
5. WHEN changes are edited THE SYSTEM SHALL call the validate endpoint and show the before/after preview and any errors or warnings.
6. WHEN the scenario is invalid THE SYSTEM SHALL disable "Run simulation" and show the errors.
7. WHEN "Run simulation" is pressed THE SYSTEM SHALL submit with a fresh `Idempotency-Key`, disable the button until the request returns, and then poll the run every second while it is `PENDING` or `RUNNING`, showing the status.
8. WHEN the run completes THE SYSTEM SHALL offer "View results" and "Compare with baseline"; WHEN it fails THE SYSTEM SHALL show the failure message.
9. WHEN the baseline scenario for the same fork has not been run THE SYSTEM SHALL offer to run it.
10. WHEN the server returns 429 THE SYSTEM SHALL tell the user the simulator is busy and to retry shortly.

### Requirement 7: Simulation Results

**User Story:** As a decision maker, I want a clear baseline-versus-alternative comparison, so that I can see
what a change would do.

#### Acceptance Criteria

1. WHEN Simulation Results loads with a run selected THE SYSTEM SHALL show the run's summary metrics, termination reason, algorithm version and fork time.
2. WHEN a baseline and an alternative are selected THE SYSTEM SHALL show a metric table (baseline, alternative, difference, percent) and Recharts charts for completion time, total cost with cost per stone, throughput, machine and employee utilization, and stage-level mean waiting time.
3. WHEN the bottleneck differs between runs THE SYSTEM SHALL show the change (same, moved, resolved, emerged) with both stages and their scores, and a per-stage evidence table of the four signals.
4. WHEN the runs have different starting states THE SYSTEM SHALL show the API's refusal and require an explicit "Compare anyway" confirmation, after which a warning stays visible.
5. WHEN a run ended on a limit THE SYSTEM SHALL show a warning that the simulation did not finish all work and list unfinished job counts.
6. WHEN results are shown THE SYSTEM SHALL show a production timeline (stage lanes over time) built from the run's simulated events.
7. WHEN any chart is shown THE SYSTEM SHALL label axes with units, name each series by scenario, and offer the same data as an accessible table.
8. WHEN directions are indicated THE SYSTEM SHALL use colour together with an icon or text, never colour alone, and SHALL show no direction for utilization.

### Requirement 8: AI Analyst

**User Story:** As a manager, I want to ask about a simulation and see the evidence behind each answer.

#### Acceptance Criteria

1. WHEN AI Analyst loads THE SYSTEM SHALL require choosing a completed run (and optionally a baseline) and show example questions for that context.
2. WHEN a question is sent THE SYSTEM SHALL show the structured answer in sections (summary, key changes, risks, bottleneck, recommendations, assumptions, limitations), tagged "AI interpretation".
3. WHEN a recommendation or statement references metrics THE SYSTEM SHALL show those evidence items (label, value, unit, source run) beside it, tagged "Simulated".
4. WHEN the response has `fallback: true` THE SYSTEM SHALL state that the built-in analyst answered because the AI provider was unavailable.
5. WHEN no completed run exists THE SYSTEM SHALL show an empty state linking to Scenario Lab.
6. WHEN the user requests an executive report THE SYSTEM SHALL request the `EXECUTIVE_REPORT` insight and offer a print-friendly view.

### Requirement 9: Settings

**User Story:** As a presenter, I want to see system status and reset the demo.

#### Acceptance Criteria

1. WHEN Settings loads THE SYSTEM SHALL show application version, API status, database status, algorithm version, and AI provider and availability from `GET /api/system/info`.
2. WHEN demo reset is enabled THE SYSTEM SHALL offer "Reset demo data" behind a typed confirmation and, on success, clear the query cache and return to Overview.

### Requirement 10: Quality

**User Story:** As a maintainer, I want the UI tested and accessible.

#### Acceptance Criteria

1. WHEN the frontend test suite runs THE SYSTEM SHALL pass component tests for every form's validation, every page's loading, empty and error states, simulation progress polling and results rendering.
2. WHEN the end-to-end suite runs against Docker Compose THE SYSTEM SHALL complete the ten-step journey in the definition of success without any database edits.
3. WHEN pages are checked with axe THE SYSTEM SHALL have no serious or critical violations, text contrast of at least 4.5:1, and full keyboard operability with visible focus.
4. WHEN the production bundle is built THE SYSTEM SHALL load route code lazily so that charts are not in the initial chunk.
