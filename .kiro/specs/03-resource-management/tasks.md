# 03 Resource Management — Tasks

Milestone: **M2 — Factory configuration** (tasks 1-6, 8). Task 7 lands in **M3** because it needs the
event store and state reconstruction. Depends on spec 01.

- [ ] 1. Add the resource schema
  - Flyway migration for `resource` and `resource_availability` with constraints and indexes.
  - Entities, enums, repositories.
  - Test: `ResourceRepositoryIT` asserts `CHECK` violations (negative capacity, zero delta, inverted window) and the unique name.
  - _Requirements: 1.1, 1.2, 1.3, 3.1, 3.2_

- [ ] 2. Implement pool creation and listing
  - `POST /api/resources`, `GET /api/resources` with `kind` filter and factory resolution.
  - Test: `ResourceApiIT` for create, duplicate → 409, validation → 400, ordering and filter.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [ ] 3. Expose `ResourceReader`
  - Records for other modules; implements `ResourceCounts`.
  - Test: unit test of mapping; no entity type appears in the interface (ArchUnit rule already covers controllers; add a rule that `*.service.*Reader` return types are not `@Entity`).
  - _Requirements: 1.4_

- [ ] 4. Implement pool update with optimistic locking
  - `PUT /api/resources/{id}`; `RequirementUsage` port and the deactivation guard (port returns empty until spec 02 implements it).
  - Test: `ResourceServiceTest` for stale version, guard, immutable kind; `ResourceApiIT` concurrent update returns one 200 and one 409.
  - _Requirements: 2.1, 2.2, 2.3, 2.5_

- [ ] 5. Implement `CapacityTimeline`
  - Pure function per the algorithm in design.md.
  - Test: `CapacityTimelineTest` examples and the jqwik property.
  - _Requirements: 3.3, 3.4_

- [ ] 6. Implement availability windows
  - `POST` and `GET /api/resources/{id}/availability`; `ResourceEventSink` port (no-op implementation until spec 04).
  - Test: `ResourceApiIT` for window creation, range query, timeline in response.
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 7. Wire events and live usage (M3)
  - `production` implements `ResourceEventSink` (appends `RESOURCE_UNAVAILABLE`) and `ResourceUsageProvider`; `ResourceResponse` gains `unitsInUse`.
  - Test: `ResourceApiIT` asserts one event per negative window in the same transaction (roll back both on failure), and `unitsInUse` for a seeded in-progress batch.
  - _Requirements: 3.5, 4.1, 4.2_

- [ ] 8. Seed demonstration resources
  - `ResourceSeedSection`: machine and employee pools for all eight stages and one past downtime window; add the values to `docs/demo-data.md`.
  - Test: `ResourceSeedIT`.
  - _Requirements: 5.1, 5.2_

- [ ] 9. Verify snapshot isolation of updates (M5)
  - Test in spec 07's `SimulationRunIT`: update a pool's capacity after a run is submitted; the run's `input_snapshot` and results are unchanged.
  - _Requirements: 2.4_
