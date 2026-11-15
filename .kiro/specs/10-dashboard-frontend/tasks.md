# 10 Dashboard and Frontend — Tasks

Milestone: **M6 — Frontend** (tasks 1-12). Task 13 is **M7** with spec 09. Tasks 14-16 are **M8**.
Depends on the Milestone 1 frontend foundation and on the APIs of specs 01-08. Each task includes its
component tests.

- [x] 1. Shared library
  - `lib/api.ts` with Zod parsing and `ApiProblem`; `lib/queryKeys.ts`; `lib/format.ts`; `lib/factory.ts`; query client defaults.
  - Test: `format.test.ts`, `api.test.ts`.
  - _Requirements: 2.4, 2.5, 2.6_

- [ ] 2. Shared components
  - `SourceTag`, `MetricTile`, `DeltaCell`, `ChartFrame`, `EmptyState`, `ErrorState`, `DataTable`, `Pagination`, `ConfirmDialog`; add the shadcn primitives they need.
  - Test: each renders its states; `DeltaCell` exposes direction as text; `MetricTile` renders "—" for null.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6, 7.8_

- [x] 3. Shell, routing and status banner
  - Ten lazy routes, sidebar with responsive collapse, header with demo badge, not-found page, backend-unreachable banner, route error boundary.
  - Test: navigation highlights; demo badge from factory flag; banner when system info fails.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 10.4_

- [x] 4. Factory page
  - Factory details and weekly calendar grid.
  - Test: loading, error, calendar with breaks.
  - _Requirements: 4.1_

- [ ] 5. Workflow explorer
  - Stage track with connectors, node contents, live counts from the state endpoint, detail sheet, optional run overlay.
  - Test: eight nodes in sequence order; counts from mocked state; overlay tagged Simulated.
  - _Requirements: 4.2, 4.3_

- [x] 6. Resource manager
  - Two tables, detail page with availability windows and capacity timeline, edit form with optimistic-lock conflict handling.
  - Test: form validation (negative capacity blocked); 409 message; kinds listed separately.
  - _Requirements: 4.4, 4.5_

- [x] 7. Production orders
  - Paginated, filterable table; order detail with batches and events.
  - Test: status filter updates the query string and request; stones and carats in separate columns; empty state.
  - _Requirements: 4.6, 4.7_

- [x] 8. Overview
  - Tiles, stage strip, baseline-simulation panel with empty state and action, output trend chart, recent events.
  - Test: every number equals the mocked payload; Simulated panel empty state when no run; tags present.
  - _Requirements: 3.1, 3.2, 3.3, 2.1_

- [x] 9. Operational timeline
  - Histogram, filters, virtualised list, event detail, instant selection, reconstructed-state panel, create-scenario dialog.
  - Test: filter requests; corrected event shows original and effective values; out-of-range instant disables the action; successful creation navigates to the scenario.
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [x] 10. Scenario Lab: list, create, branch, rename
  - Scenario table, new and branch dialogs, inline rename with version handling.
  - Test: create and branch requests; rename conflict message.
  - _Requirements: 6.1, 6.2_

- [x] 11. Scenario Lab: changes, validation preview and run
  - Six change forms with Zod schemas and server error mapping; preview panel; run panel with idempotency key, polling, completion and failure states, baseline prompt, 429 handling.
  - Test: one validation test per form; run disabled when invalid; polling with fake timers for success and failure; idempotency header; 429 message.
  - _Requirements: 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10_

- [x] 12. Simulation results
  - Pickers, warnings, metric table, charts in `ChartFrame`, bottleneck card with signals, order delivery table, production timeline, different-baseline consent.
  - Test: values equal the mocked comparison verbatim; bottleneck change variants; limit warning; consent flow; table alternative for each chart.
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8_

- [x] 13. AI Analyst (M7)
  - Context pickers, example questions, structured answer cards with evidence drawers, fallback notice, insight history, executive report print view.
  - Test: sections render; evidence beside recommendations tagged Simulated; fallback notice; empty state without runs.
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6_

- [x] 14. Settings (M8)
  - System information and guarded demo reset.
  - Test: reset requires typed confirmation; cache cleared and navigation on success; control hidden when reset is disabled.
  - _Requirements: 9.1, 9.2_

- [x] 15. Accessibility and responsive pass (M8)
  - `vitest-axe` checks per page; keyboard walkthrough; contrast check of tokens; layouts at 375, 768, 1024 and 1440 px.
  - _Requirements: 1.2, 10.3_

- [x] 16. End-to-end journey (M8)
  - Playwright project and `e2e/journey.spec.ts` covering the ten steps against Docker Compose; document the command in `docs/setup.md`.
  - _Requirements: 10.1, 10.2_

## Implementation notes (Milestones 2, 3 and 6)

The screens for tasks 1, 3, 4, 6, 7 and the recorded-history parts of 8 and 9 were built with the backend
milestones that feed them (2 and 3); Milestone 6 added Scenario Lab, Simulation Results, the simulated
baseline panel on Overview and "Create scenario from this point" on the Timeline.

- **Task 2 is left open.** There is no generic `DataTable`, `MetricTile`, `ChartFrame` or `ConfirmDialog`:
  each feature uses the shared `ui/table`, `Card` and `Dialog` primitives with small local components
  (`features/results/resultsUi.tsx`, the scenario confirm dialog). `SourceTag` gained "Simulated" (with
  the scenario name), and `DeltaCell` (`components/Delta.tsx`) and `RunStatusBadge` are shared.
- **Task 5 is left open:** the workflow explorer has no simulation-run overlay yet.
- **Scenario Lab** (`features/scenarios`): list with new and branch dialogs; detail page with inline
  rename and description (optimistic locking with a reload option), six change forms with Zod rules
  mirroring `ChangePayloads.java` and server field errors mapped back, a "Check before running" panel
  (validate endpoint, errors, warnings, before/after preview showing changed rows first), and a run panel:
  fresh `Idempotency-Key` per press, polling every second, reused-run, 422, 429 and failure messages,
  "View results" and "Compare with baseline", and "Run baseline" when the matching baseline has not run.
- **Simulation Results** (`features/results`): URL state `?run=&baseline=&allowDifferent=1`. Pickers list
  each scenario's latest completed run. A single run shows summary tiles (nulls as "—" with the reason),
  bottleneck evidence, pools and orders; a comparison shows warnings, applied changes from each run's
  snapshot, the metric table with directions as arrow and word as well as colour, the bottleneck change,
  six bar charts each with a table alternative, and stage, pool and order comparisons. A different starting
  state shows the API's refusal and needs "Compare anyway". The production timeline is an SVG Gantt from
  the run's simulated events (up to ten pages of 200), with a table alternative.
- Run tests use real timers and wait about one poll interval rather than fake timers.
- Charts are not drawn in jsdom; tests check their table alternatives, and the charts were checked in a
  browser against the running stack.

## Implementation notes (Milestone 7)

Task 13 is built in `features/analyst/`. The context pickers default to the latest non-baseline run, and to the
baseline scenario's run when one exists. There are report buttons for each insight type and example questions
for the chosen context. Each answer card has sections, an evidence disclosure beside the summary and beside
every recommendation (tagged Simulated, with the source run's name), a fallback notice saying why the
built-in analyst answered, and the run's insight history. An executive report prints on its own through print
CSS (`body.printing-insight`). The test fixtures `aiInsight`, `aiAnswer` and `aiFallbackInsight` are real
responses captured with Claude Haiku 5.5.

## Implementation notes (Milestone 8)

- **Task 14.** Settings shows the simulation algorithm version (new `simulation.algorithmVersion` in
  `GET /api/system/info`, supplied by `simulation` through `common.system.AlgorithmVersionSource` so that
  `common` depends on no feature). Reset needs RESET typed, clears the whole query cache and returns to Overview,
  which confirms the reset.
- **Task 15.** Done in a real browser rather than with `vitest-axe`: jsdom cannot compute colour contrast.
  `e2e/accessibility.spec.ts` runs axe (WCAG 2.1 A and AA) on all ten screens at 375, 768, 1024 and 1440 px, fails
  on serious or critical violations or sideways page scroll, and checks keyboard focus on the navigation. It found
  and we fixed: Recharts surfaces focusable inside `aria-hidden` chart wrappers (`accessibilityLayer={false}`; each
  chart has a table alternative), and scroll areas the keyboard could not reach (`useScrolls` makes a table box, and
  `main`, focusable with a visible ring only while it actually scrolls).
- **Task 16.** `e2e/journey.spec.ts` follows the twelve steps of the definition of success against Docker Compose,
  through the UI only. Building it exposed two gaps, now fixed: a scenario branched from its own Timeline moment had
  no baseline to compare with (Scenario Lab now offers **Create and run baseline**: a scenario with no changes from
  the same moment), and Simulation Results had no way to the AI Analyst (**Explain with AI Analyst** carries the run
  and baseline). Command and options in `docs/setup.md`; walkthrough in `docs/demo.md`.
- Tasks 2 and 5 stay open as described above (no generic shared components; no simulation overlay on the workflow
  explorer).
