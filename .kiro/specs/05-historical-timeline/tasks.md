# 05 Historical Timeline — Tasks

Milestones: **M3 — Production operations** for the backend (tasks 1-7); **M6 — Frontend** for the timeline
screen (task 8, detailed in spec 10). Depends on spec 04.

- [ ] 1. Implement `CanonicalHasher` in `common`
  - Dedicated mapper, sorted keys, normalised decimals, SHA-256 hex.
  - Test: `CanonicalHasherTest`.
  - _Requirements: 3.6_

- [ ] 2. Implement `FactoryStateReconstructor`
  - Records `FactoryState`, `BatchState`, `OrderState`, `StageCounts`; algorithm from design.md reusing `EffectiveEvents` and `BatchProjector`.
  - Test: `FactoryStateReconstructorTest`; jqwik `ReconstructionProperties`.
  - _Requirements: 3.1, 3.2, 3.4, 3.5, 3.6, 3.8, 4.1, 4.2_

- [ ] 3. Expose the state endpoint
  - `GET /api/factories/{id}/state?at=`, default `at` = now.
  - Test: `FactoryStateIT` against seeded history, including the no-writes assertion.
  - _Requirements: 3.1, 3.3, 3.4, 3.5, 3.6, 3.7_

- [ ] 4. Implement the timeline query
  - `TimelineService` with filters, enrichment via joins, effective values, `includeVoided`, `order`.
  - Test: `TimelineApiIT` filter matrix and pagination.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [ ] 5. Implement the timeline summary
  - `GET /api/timeline/summary` with `HOUR` and `DAY` buckets in the factory zone.
  - Test: `TimelineApiIT` bucket boundaries at local midnight; empty factory.
  - _Requirements: 2.1, 2.2, 2.3_

- [ ] 6. Implement the overview endpoint
  - `FactoryOverviewService` and `GET /api/factories/{id}/overview`.
  - Test: `FactoryStateIT` compares each figure with a direct SQL count.
  - _Requirements: 5.1, 5.2_

- [ ] 7. Document the endpoints
  - OpenAPI annotations; Timeline and State sections in `docs/api.md`, including what "effective" means.
  - _Requirements: 1.3, 3.8_

- [ ] 8. Build the timeline screen (M6)
  - Implemented under spec 10, task "Operational Timeline". The fork flow shows the reconstructed state and then calls `POST /api/scenarios`.
  - _Requirements: 6.1, 6.2, 6.3_
