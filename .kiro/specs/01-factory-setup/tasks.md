# 01 Factory Setup — Tasks

Milestone: **M2 — Factory configuration**. Depends on the Milestone 1 foundation (Spring Boot app, Flyway,
problem+json advice, Testcontainers base class).

- [x] 1. Add the factory schema
  - Write a Flyway migration creating `factory`, `working_shift`, `calendar_break` with the constraints and indexes in design.md.
  - Add `Factory`, `WorkingShift`, `CalendarBreak` entities and repositories; start-up must pass `ddl-auto=validate`.
  - Test: `FactoryRepositoryIT` persists a factory with two shifts and one break and reads them back ordered.
  - _Requirements: 1.1, 2.1_

- [x] 2. Implement factory creation with calendar validation
  - `CreateFactoryRequest` and nested calendar records with Bean Validation.
  - `FactoryService.create`: zone id check, duplicate name → 409, overlapping shifts, break inside shift.
  - Test: `FactoryServiceTest` covers each rejection and the happy path.
  - _Requirements: 1.1, 1.2, 1.3, 2.2, 2.3, 2.4, 2.6_

- [x] 3. Implement factory read endpoints
  - `FactoryController` with `GET /api/factories` (list envelope) and `GET /api/factories/{id}`.
  - `FactoryResponse` includes calendar and `weeklyWorkingSeconds`; counts come from the `*Counts` interfaces (return zero until other modules implement them).
  - Test: `FactoryApiIT` for 200, 404, pagination, ordering, and error body shape.
  - _Requirements: 1.4, 1.5, 2.5, 3.1, 3.2_

- [x] 4. Add `FactoryResolver` to `common`
  - Implements the factory-resolution rule in design.md.
  - Test: unit test for the four cases (given and exists, given and missing, omitted with one, omitted with zero or many).
  - _Requirements: 1.5_

- [x] 5. Add `FactoryCalendarService`
  - Returns an immutable `FactoryCalendar` (zone, shifts with breaks) for a factory id.
  - Test: unit test that shifts are ordered and breaks attached.
  - _Requirements: 2.1, 2.5_

- [x] 6. Build the demo seed framework and the factory section
  - `DemoSeedSection`, `DemoSeedContext` (anchor instant, deterministic ids), `DemoSeeder`, `DemoStartupRunner`, properties `realitycheck.demo.seed-on-startup` and `realitycheck.demo.reset-enabled`.
  - `FactorySeedSection` creates Surat Diamond Works and its calendar.
  - Test: `DemoSeederIT` for determinism, skip-when-not-empty, and rollback on section failure.
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 7. Implement demo reset
  - `DemoResetService` and conditional `DemoController`.
  - Test: `DemoResetIT` for restore, 409 with an active run (insert a RUNNING row directly once spec 07's table exists; until then assert the query path with an empty table), and 404 when disabled.
  - _Requirements: 5.1, 5.2, 5.3_

- [x] 8. Document the endpoints
  - Add OpenAPI annotations (summary, response codes) and a Factory section in `docs/api.md`.
  - _Requirements: 1.1, 1.4, 1.5_

## Implementation notes (Milestone 2)

Differences from design.md, made during implementation:

- `FactoryResolver` lives in `factory.service`, not `common`: `common` may not depend on a feature module.
- Counts use one port, `FactoryCountProvider { key(); count(factoryId) }`, instead of three interfaces.
  `FactoryResponse.counts` is a map that gains a key as each module is built.
- `FactoryCalendarService` is `FactoryService.calendar(factoryId)`, returning the `FactoryCalendar` record.
- Seed sections all live in `com.realitycheck.demo`, and `DemoSeeder.reset()` replaces `DemoResetService`.
  The table list for `TRUNCATE` is read from the catalog, so new tables are covered automatically.
- The reset response is `{ factoryId, resetAt }`.
- The "refuse while a run is active" check (requirement 5.2) is not implemented: `simulation_run` does not
  exist yet. It must be added with spec 07 task 15.
- Factory and calendar are covered by `CalendarValidatorTest`, `FactoryApiIT` and `DemoIT` rather than the
  separate `FactoryRepositoryIT`, `FactoryServiceTest`, `DemoSeederIT` and `DemoResetIT` named above.
