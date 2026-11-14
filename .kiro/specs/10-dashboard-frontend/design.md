# 10 Dashboard and Frontend — Design

## Structure

Follows architecture.md, "Frontend modules".

```
src/app/            router.tsx, providers.tsx, AppShell.tsx, Sidebar.tsx, Header.tsx, NotFound.tsx
src/components/ui/  shadcn primitives (button, card, dialog, input, select, table, tabs, badge, skeleton, tooltip, sheet, toast)
src/components/     PageHeader, SourceTag, StatusBadge, MetricTile, DeltaCell, EmptyState, ErrorState,
                    DataTable, Pagination, ChartFrame, ConfirmDialog
src/features/<f>/   routes.tsx, queries.ts, schemas.ts, components/*, __tests__/*
src/lib/            api.ts, problem.ts, queryKeys.ts, format.ts, factory.ts (active-factory hook), polling.ts
```

Features: `overview`, `factory`, `workflow`, `resources`, `orders`, `timeline`, `scenarios`, `results`,
`analyst`, `settings`. Features import from `lib` and `components` only.

## Routes

| Path | Feature | Notes |
|---|---|---|
| `/` | overview | |
| `/factory` | factory | |
| `/workflow` | workflow | `?stage=<id>` opens the detail panel; `?run=<id>` overlays simulated stage metrics |
| `/resources`, `/resources/:id` | resources | |
| `/orders`, `/orders/:id` | orders | `?status=&page=` |
| `/timeline` | timeline | `?at=<instant>&stage=&order=&type=` |
| `/scenarios`, `/scenarios/:id` | scenarios | Scenario Lab |
| `/results` | results | `?run=<id>&baseline=<id>` |
| `/analyst` | analyst | `?run=<id>&baseline=<id>` |
| `/settings` | settings | |

Selection state (run, baseline, filters, selected instant) lives in the URL so views are linkable and
survive reload. Routes are lazy-loaded with `React.lazy`; Recharts is only imported by `overview` and
`results` chunks.

## Data access

- `lib/api.ts`: `request<T>(method, path, { query, body, headers, schema })`. Parses the response with the
  given Zod schema; on a non-2xx response parses `application/problem+json` into `ApiProblem` (`status`,
  `title`, `detail`, `errors[]`) and throws it. Base URL is `/api` (same origin through the Vite dev proxy or
  nginx). No component calls `fetch`.
- Zod schemas live in each feature's `schemas.ts`; decimal strings stay strings in state and are converted
  only in `lib/format.ts` for display or charts (`Number()` is acceptable for plotting, never for arithmetic
  shown as a result; differences come from the comparison API).
- `lib/queryKeys.ts`: one factory of keys per resource, all prefixed by factory id, for example
  `['factory', fid, 'orders', { status, page }]`, `['run', runId]`, `['run', runId, 'results']`,
  `['compare', baselineId, altId]`.
- Defaults: `staleTime` 30 s for configuration and history; `Infinity` for completed-run results and
  comparisons (immutable); `retry` 1 for queries, 0 for mutations.
- `lib/factory.ts`: `useActiveFactory()` loads `GET /api/factories`, selects the first (the MVP has one) and
  exposes `id`, `timeZone`, `currencyCode`, `demo`.
- `lib/polling.ts`: `useRunStatus(runId)` uses `refetchInterval: (q) => status is PENDING or RUNNING ? 1000 : false`;
  on `COMPLETED` it invalidates the scenario list and the scenario's comparisons.

## Shared components

| Component | Behaviour |
|---|---|
| `SourceTag` | `kind: 'ACTUAL' \| 'SIMULATED' \| 'AI'`; fixed label, icon and token colour; optional detail (scenario name, provider). Every panel that shows numbers takes a required `source` prop, so an untagged panel does not type-check. |
| `MetricTile` | Label, value, unit, optional tooltip with exact value; `null` renders "—" with a reason. |
| `DeltaCell` | Absolute and percent difference from the comparison API with direction icon (arrow plus "improved" / "worsened" text for screen readers); neutral when `direction` is null. |
| `ChartFrame` | Title, unit, `SourceTag`, the chart, and a "View as table" toggle rendering the same series. |
| `EmptyState`, `ErrorState` | Message plus primary action; `ErrorState` takes an `ApiProblem` and a retry callback. |
| `DataTable` | Typed columns, loading skeleton rows, empty row, server pagination. |

`lib/format.ts`: `formatDuration(seconds)` ("6.5 h", "3.2 days" from 48 h upward, exact seconds in the
tooltip), `formatMoney(value, currency)` with `Intl.NumberFormat`, `formatStones` (whole, exact on hover),
`formatCarats` (2 decimals), `formatPercent(fraction)`, `formatInstant(iso, timeZone)` in the factory zone
with the zone abbreviation shown.

## Visual design

Tokens are CSS variables in `src/index.css` consumed through Tailwind; components never use raw hex.

| Token | Role |
|---|---|
| `--background`, `--surface`, `--surface-raised` | deep navy / charcoal layers |
| `--border`, `--muted-foreground`, `--foreground` | hairlines and text |
| `--primary` (blue), `--accent` (violet) | interactive and highlight |
| `--success`, `--warning`, `--danger` | status only |
| `--actual`, `--simulated`, `--ai` | the three provenance colours, used only by `SourceTag` and chart series |
| `--chart-1` … `--chart-5` | series palette, checked for contrast on `--surface` |

Rules: one accent per view; borders before shadows; no decorative gradients; motion limited to 150 ms
transitions and skeleton shimmer, disabled under `prefers-reduced-motion`; density closer to a console than a
marketing page (14 px base, tabular numerals for all figures); at most one row of four `MetricTile`s per page
before content moves into tables or charts.

## Screens

**Overview.** Row of four tiles (active, pending, completed, delayed) tagged Actual. Left: stage strip (eight
compact cells with queued and in-progress counts, link to Workflow). Right: "Latest baseline simulation"
panel tagged Simulated (utilization, throughput, bottleneck) or its empty state. Below: 14-day output area
chart (stones, with carats as a second table column, not a second axis) and recent events list.

**Workflow.** A horizontal scrolling track of eight nodes joined by connectors, built with flex and SVG
connectors (no React Flow while the workflow is linear, per tech.md). Node: name, sequence, rate, queued and
in-progress counts; a marker when it is the selected run's primary bottleneck. Side sheet for details.

**Resources.** Two tables (Machines, Employees). Row → detail page with capacity timeline (step chart) and
edit form (`capacity`, `hourlyCost`, `status`, hidden `version`).

**Orders.** Server-paginated table and detail page with batch cards and event list.

**Timeline.** Top: histogram of events per day (bar chart), click selects the end of that day; a date-time
input for exact selection. Middle: filters and virtualised event list. Right panel: reconstructed state at
the selected instant (per-stage queued and in-progress, order counts, unavailable pools) with the
"Create scenario from this point" button.

**Scenario Lab.** List view: table of scenarios with a "New scenario" and per-row "Branch" action. Detail
view in three columns: (1) scenario header with inline rename, fork time, parent link; (2) change list with
add, edit and delete, each change shown by its server `summary`; (3) preview from the validate endpoint
(before/after capacities, rates, weekly hours, warnings, errors) and the run panel (button, status stepper
PENDING → RUNNING → COMPLETED or FAILED, links to results and comparison).

Change forms are one component per type sharing a `ChangeFormShell`. Zod schemas mirror spec 06's payload
rules; the server remains the authority and its 400 `errors[]` are mapped back onto fields by name.

**Results.** Header: run and baseline pickers (only completed runs; baseline defaults from
`GET /api/scenarios/{id}/comparisons`). Sections: warnings; metric table; charts (grouped bars for completion
time, cost and cost per stone, throughput; utilization bars per pool; stage waiting-time bars by stage for
both runs); bottleneck card with the signals table; order delivery table; production timeline (one lane per
stage, a bar from start to completion per job, from `/events`, capped to the first 500 jobs with a note).

**AI Analyst.** Left: context pickers and example questions. Main: a list of question-and-answer cards, each
answer rendered as sections with an evidence drawer per recommendation. It is a structured report view with
an input at the bottom, not a chat-bubble layout. Insight history from `GET /api/ai/insights/{runId}`.

**Settings.** System information table and the guarded demo reset.

## Error handling

- A global `QueryCache` `onError` shows a toast for background refetch failures; first-load failures render
  `ErrorState` in place.
- Mutations surface `ApiProblem.detail`; 409 on resource update prompts "This was changed elsewhere — reload".
- An error boundary per route catches render errors and offers reload.
- Backend-unreachable detection: failed `GET /api/system/info` (polled every 30 s) drives the header banner.

## Testing

Vitest, Testing Library and MSW (request mocking at the network level, added as a dev dependency for this
purpose) for component tests; Playwright for end to end.

- `lib/format.test.ts`, `lib/api.test.ts` (schema failure, problem parsing).
- Per feature: loading, empty and error states; key interactions.
- `scenarios`: each change form's validation; server error mapping; run button disabled when invalid; polling
  transitions with fake timers (PENDING → RUNNING → COMPLETED and → FAILED); idempotency header present; 429 message.
- `results`: table values equal the mocked comparison payload verbatim; null rendering; different-baseline
  consent flow; limit warning.
- `analyst`: sections and evidence rendering; fallback notice.
- Accessibility: `vitest-axe` on each page in its loaded state.
- `e2e/journey.spec.ts` (Playwright against Docker Compose with the seeded demo): the ten steps of the
  definition of success, selecting elements by role and accessible name.
