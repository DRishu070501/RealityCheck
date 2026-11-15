# 03 Resource Management — Tasks

Milestone: **M2 — Factory configuration** (tasks 1-6, 8). Task 7 lands in **M3** because it needs the
event store and state reconstruction. Depends on spec 01.

- [x] 1. Add the resource schema
  - Flyway migration for `resource` and `resource_availability` with constraints and indexes.
  - Entities, enums, repositories.
  - Test: `ResourceRepositoryIT` asserts `CHECK` violations (negative capacity, zero delta, inverted window) and the unique name.
  - _Requirements: 1.1, 1.2, 1.3, 3.1, 3.2_

- [x] 2. Implement pool creation and listing
  - `POST /api/resources`, `GET /api/resources` with `kind` filter and factory resolution.
  - Test: `ResourceApiIT` for create, duplicate → 409, validation → 400, ordering and filter.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 3. Expose `ResourceReader`
  - Records for other modules; implements `ResourceCounts`.
  - Test: unit test of mapping; no entity type appears in the interface (ArchUnit rule already covers controllers; add a rule that `*.service.*Reader` return types are not `@Entity`).
  - _Requirements: 1.4_

- [x] 4. Implement pool update with optimistic locking
  - `PUT /api/resources/{id}`; `RequirementUsage` port and the deactivation guard (port returns empty until spec 02 implements it).
  - Test: `ResourceServiceTest` for stale version, guard, immutable kind; `ResourceApiIT` concurrent update returns one 200 and one 409.
  - _Requirements: 2.1, 2.2, 2.3, 2.5_

- [x] 5. Implement `CapacityTimeline`
  - Pure function per the algorithm in design.md.
  - Test: `CapacityTimelineTest` examples and the jqwik property.
  - _Requirements: 3.3, 3.4_

- [x] 6. Implement availability windows
  - `POST` and `GET /api/resources/{id}/availability`; `ResourceEventSink` port (no-op implementation until spec 04).
  - Test: `ResourceApiIT` for window creation, range query, timeline in response.
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 7. Wire events and live usage (M3)
  - `production` implements `ResourceEventSink` (appends `RESOURCE_UNAVAILABLE`) and `ResourceUsageProvider`; `ResourceResponse` gains `unitsInUse`.
  - Test: `ResourceApiIT` asserts one event per negative window in the same transaction (roll back both on failure), and `unitsInUse` for a seeded in-progress batch.
  - _Requirements: 3.5, 4.1, 4.2_

- [x] 8. Seed demonstration resources
  - `ResourceSeedSection`: machine and employee pools for all eight stages and one past downtime window; add the values to `docs/demo-data.md`.
  - Test: `ResourceSeedIT`.
  - _Requirements: 5.1, 5.2_

- [x] 9. Verify snapshot isolation of updates (M5)
  - Test in spec 07's `SimulationRunIT`: update a pool's capacity after a run is submitted; the run's `input_snapshot` and results are unchanged.
  - _Requirements: 2.4_

## Implementation notes (Milestone 2)

- `GET /api/resources/{id}` was added; the edit form and the workflow view need a single pool.
- Lists are ordered machines first, then employees, each by name.
- `GET .../availability` without `from` and `to` spans all of the pool's windows rather than "30 days around
  now": the demonstration data is anchored in January 2026 and would otherwise show nothing.
- `ResourceEventSink` and `ResourceUsageProvider` are not declared yet. They arrive with task 7 in
  Milestone 3, together with their implementations, instead of as no-op placeholders now.
- The ArchUnit rule on `*Reader` return types was not added; `ResourceReader` returns records only.
- Optimistic locking is tested with a stale version, not with two concurrent requests.

## Implementation notes (Milestone 3)

- Task 7: a negative availability window appends one `RESOURCE_UNAVAILABLE` event in the same transaction.
  Units in use are exposed through the factory state and overview endpoints, not on `ResourceResponse`.

## Implementation notes (Milestone 8 review)

Task 9 is covered by `SimulationRunIT.editingConfigurationOrTheScenarioAfterARunLeavesTheRunUntouched` (a pool is
renamed and resized after a run; the run's stored input and results are unchanged) and
`theStoredInputOfARunCannotBeRewritten`.
