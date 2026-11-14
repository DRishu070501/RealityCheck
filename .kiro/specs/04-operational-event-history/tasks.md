# 04 Operational Event History — Tasks

Milestone: **M3 — Production operations**. Depends on specs 01, 02, 03.

- [ ] 1. Add the production schema
  - Flyway migration for `production_order`, `production_batch`, `operational_event`, indexes, and the append-only trigger.
  - Entities (event mapped immutable), enums, repositories.
  - Test: `OperationalEventIT` proves `UPDATE` and `DELETE` are rejected by the trigger and `INSERT` works.
  - _Requirements: 2.2, 2.3_

- [ ] 2. Implement `EffectiveEvents` and `BatchProjector`
  - Pure functions per design.md; no Spring.
  - Test: `EffectiveEventsTest`, `BatchProjectorTest`.
  - _Requirements: 4.3, 4.4, 5.1, 5.4_

- [ ] 3. Implement `EventValidator` and the batch state machine
  - Reference and ownership checks, transition table, quantity bounds, time ordering.
  - Test: unit test per row of the transition table and per rejection; jqwik `BatchStateMachineProperties`.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 5.4_

- [ ] 4. Implement `EventAppendService`
  - Row locking, insert, follow-on `STAGE_QUEUED` and `ORDER_COMPLETED`, projection update.
  - Test: `EventAppendServiceTest`; `OperationalEventIT` concurrent-append case.
  - _Requirements: 2.1, 3.6, 3.7, 3.8, 5.1, 5.2_

- [ ] 5. Implement order creation and reads
  - `OrderService`, `OrderController`; `delayed` derivation; implements `factory`'s `OrderCounts`.
  - Test: `OrderApiIT` for atomic creation (order + batches + events), duplicates, validation, list filters, detail with events, `delayed` in both cases (late-open and completed-late).
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 5.3_

- [ ] 6. Implement the event endpoints
  - `POST /api/events` (delegates to `EventAppendService`), `GET /api/events` with filters and pagination; future-time guard; payload size limit.
  - Test: `EventApiIT` for each filter, ordering by `(occurredAt, seq)`, size cap, 400 and 422 bodies.
  - _Requirements: 2.1, 2.4, 6.1, 6.2_

- [ ] 7. Implement corrections
  - `applyCorrection` with full-sequence revalidation and projection rebuild.
  - Test: correcting a quantity changes the projection and leaves the original row byte-identical; voiding a middle event is rejected; correcting a correction is rejected.
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [ ] 8. Implement the resource ports
  - `ResourceEventSink` and `ResourceUsageProvider` from spec 03 (see spec 03 task 7).
  - _Requirements: 2.1_

- [ ] 9. Seed demonstration history
  - `ProductionSeedSection`: a small deterministic generator that walks orders through stages using the seeded rates and calendar, emitting events via `EventAppendService` with explicit `occurredAt`; stops at the seed's "now" (anchor + 14 days) leaving work in progress; includes one correction and one downtime event.
  - Test: `ProductionSeedIT`.
  - _Requirements: 7.1, 7.2, 7.3_

- [ ] 10. Guard history against simulation writes (M5)
  - Test in spec 07's `SimulationRunIT`: row counts and a checksum of `operational_event`, `production_order`, `production_batch` are identical before and after a completed run and a failed run.
  - _Requirements: 2.5_

- [ ] 11. Document the endpoints
  - OpenAPI annotations; Orders and Events sections in `docs/api.md` including the transition table.
  - _Requirements: 3.2, 3.3_
