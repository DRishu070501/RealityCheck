# 02 Production Workflow — Tasks

Milestone: **M2 — Factory configuration**. Depends on spec 01 (factory) and spec 03 tasks 1-3 (resource
table and `ResourceReader`).

- [x] 1. Add the workflow schema
  - Flyway migration for `workflow`, `workflow_stage`, `stage_requirement`, the partial unique index and `CHECK` constraints.
  - Entities, `WorkflowStatus`, repositories.
  - Test: `WorkflowRepositoryIT` asserts constraint violations for rate 0, yield 1.1, duplicate sequence, and a second ACTIVE workflow.
  - _Requirements: 1.1, 1.4, 2.1, 2.2, 3.1_

- [x] 2. Implement workflow creation
  - Request records with Bean Validation; `WorkflowService.create` applying validation rules 1-5 from design.md.
  - Test: `WorkflowServiceTest` with one test per rule plus the happy path.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 3.1, 3.2, 3.3, 3.4_

- [x] 3. Implement workflow read endpoints
  - `GET /api/workflows` (factory-scoped via `FactoryResolver`), `GET /api/workflows/{id}`, `GET /api/workflows/{id}/stages` with requirement details and `maxSlots`.
  - Test: `WorkflowApiIT` for ordering, 404, decimal-as-string serialisation, `maxSlots`.
  - _Requirements: 1.5, 1.6, 2.3, 3.5_

- [ ] 4. Expose `WorkflowReader` for other modules
  - Returns `WorkflowView` records for the active workflow of a factory; implements `factory`'s `WorkflowCounts`.
  - Test: unit test that stages are sorted by sequence and requirements by resource id.
  - _Requirements: 1.6, 4.1_

- [x] 5. Seed the demonstration workflow
  - `WorkflowSeedSection` creating the eight stages with synthetic parameters and requirements; document the chosen values in a table in `docs/demo-data.md` headed "Synthetic data".
  - Test: `WorkflowSeedIT`.
  - _Requirements: 5.1, 5.2, 5.3_

- [x] 6. Document the endpoints
  - OpenAPI annotations and a Workflow section in `docs/api.md`, stating units for every stage field.
  - _Requirements: 2.3_

## Implementation notes (Milestone 2)

- Task 4 is partly done: `workflow` implements `FactoryCountProvider` and `RequirementUsage`. The
  `WorkflowReader` view for snapshot assembly is deferred to Milestone 5, where its consumer is built.
- Requirements in a stage response are ordered machines first, then employees, each by pool name.
- Validation is the pure `WorkflowValidator`, covered by `WorkflowValidatorTest`; database constraints and
  the API are covered by `WorkflowApiIT`; the seed by `DemoIT`.
