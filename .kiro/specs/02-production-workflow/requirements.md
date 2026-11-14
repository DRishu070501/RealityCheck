# 02 Production Workflow — Requirements

## Introduction

A workflow is the ordered list of stages a batch of diamonds passes through. The MVP supports exactly one
active, strictly sequential workflow per factory. The demonstration workflow has eight stages: Rough Intake,
Planning, Marking, Cutting, Bruting, Polishing, Quality Inspection, Dispatch.

A stage carries the parameters the simulation engine needs: processing rate (stones per hour per processing
slot), setup time, stone yield, carat yield, a fixed cost per job, and its resource requirements. Units are
defined in simulation-rules.md section 4.

## Requirements

### Requirement 1: Workflow definition

**User Story:** As a production planner, I want to define the factory's workflow as an ordered list of
stages, so that the digital twin mirrors how work actually flows.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/workflows` with a factory id, a name and one or more stages THE SYSTEM SHALL create the workflow and its stages in one transaction and return 201.
2. WHEN the stage `sequence` values are not exactly 1..N without gaps or duplicates THE SYSTEM SHALL return 400.
3. WHEN two stages in the same workflow share a `code` THE SYSTEM SHALL return 400.
4. WHEN a workflow is created with `status: ACTIVE` and the factory already has an active workflow THE SYSTEM SHALL return 409.
5. WHEN a client sends `GET /api/workflows?factoryId=` THE SYSTEM SHALL return the factory's workflows in the list envelope.
6. WHEN a client sends `GET /api/workflows/{id}` THE SYSTEM SHALL return the workflow with its stages in sequence order.

### Requirement 2: Stage parameters

**User Story:** As an operations manager, I want each stage to hold its rate, setup, yields and cost with
explicit units, so that simulated times and quantities are unambiguous.

#### Acceptance Criteria

1. WHEN a stage is created THE SYSTEM SHALL require `stonesPerHour` greater than zero, `setupSeconds` zero or greater, `stoneYield` and `caratYield` greater than zero and at most 1, and `fixedCostPerJob` zero or greater.
2. WHEN any of those constraints is violated THE SYSTEM SHALL return 400 naming the field, and the database SHALL also enforce the same constraints with `CHECK` clauses.
3. WHEN a stage is returned THE SYSTEM SHALL express the rate as `stonesPerHour` (per processing slot), setup as `setupSeconds`, and yields as fractions, never as percentages.

### Requirement 3: Resource requirements

**User Story:** As a production manager, I want to state which resource pools a stage needs for one
processing slot, so that the simulation only starts work when the whole set is free.

#### Acceptance Criteria

1. WHEN a stage is created with requirements THE SYSTEM SHALL store one `stage_requirement` per (stage, resource pool) with `unitsPerSlot` of at least 1.
2. WHEN a requirement references a resource pool that does not exist or belongs to another factory THE SYSTEM SHALL return 400.
3. WHEN the same pool is listed twice for one stage THE SYSTEM SHALL return 400.
4. WHEN a stage has no requirements THE SYSTEM SHALL accept it; such a stage has unlimited slots in simulation.
5. WHEN a client sends `GET /api/workflows/{id}/stages` THE SYSTEM SHALL return each stage with its requirements, including the pool's name, kind and current capacity, and the derived `maxSlots` (minimum over requirements of `floor(capacity / unitsPerSlot)`; `null` when there are no requirements).

### Requirement 4: Workflow stability

**User Story:** As a user comparing scenarios, I want the workflow structure to stay fixed once production
has been recorded against it, so that history remains interpretable.

#### Acceptance Criteria

1. WHEN any `operational_event` references a stage of a workflow THE SYSTEM SHALL treat that workflow's structure (stage set, codes, sequence) as immutable; the MVP exposes no endpoint that changes it.
2. WHEN a resource pool referenced by a `stage_requirement` is deleted or deactivated THE SYSTEM SHALL refuse with 409 (enforced in spec 03).

### Requirement 5: Demonstration workflow

**User Story:** As a judge, I want the eight diamond-manufacturing stages preconfigured, so that I can see a
complete workflow immediately.

#### Acceptance Criteria

1. WHEN the demo seed runs THE SYSTEM SHALL create one ACTIVE workflow "Diamond Manufacturing" for Surat Diamond Works with the eight stages in the order given in the introduction, codes `ROUGH_INTAKE`, `PLANNING`, `MARKING`, `CUTTING`, `BRUTING`, `POLISHING`, `QUALITY_INSPECTION`, `DISPATCH`.
2. WHEN the demo seed runs THE SYSTEM SHALL give every stage synthetic parameters and requirements that reference seeded pools (spec 03), chosen so that Polishing is the constrained stage under the seeded order load.
3. WHEN stage data is displayed for the demo factory THE SYSTEM SHALL mark it as synthetic through the factory's `demo` flag.
