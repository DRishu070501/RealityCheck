# 01 Factory Setup — Requirements

## Introduction

A factory is the root of everything else in RealityCheck: workflow, resource pools, orders, events and
scenarios all carry its `factory_id`. This spec covers the factory record, its weekly working calendar
(shifts and breaks), the factory API, and the framework that seeds and resets the demonstration factory
"Surat Diamond Works". Other specs add their own sections to the seed.

Authentication and multi-tenancy are out of scope (see product.md, non-goals).

## Requirements

### Requirement 1: Factory record

**User Story:** As a production manager, I want my factory registered with its time zone and currency, so
that schedules and costs are interpreted correctly.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/factories` with a name, IANA time zone and ISO-4217 currency code THE SYSTEM SHALL create the factory with an application-generated UUID and return 201 with a `Location` header.
2. WHEN the name is blank, longer than 120 characters, or already used by another factory THE SYSTEM SHALL reject the request with 400 (validation) or 409 (duplicate) as `application/problem+json`.
3. WHEN the time zone is not a valid IANA zone id or the currency is not three upper-case letters THE SYSTEM SHALL return 400 with an `errors` entry naming the field.
4. WHEN a client sends `GET /api/factories` THE SYSTEM SHALL return a paginated list in the standard list envelope, ordered by name.
5. WHEN a client sends `GET /api/factories/{id}` for an unknown id THE SYSTEM SHALL return 404.

### Requirement 2: Working calendar

**User Story:** As an operations manager, I want to define the factory's weekly shifts and breaks, so that
simulations only schedule work when the factory is open.

#### Acceptance Criteria

1. WHEN a factory is created with a `calendar` THE SYSTEM SHALL store one `working_shift` row per shift (ISO day of week 1-7, local start time, local end time) and one `calendar_break` row per break.
2. WHEN a shift's end time is not after its start time THE SYSTEM SHALL return 400. A shift that crosses midnight is entered as two shifts.
3. WHEN two shifts on the same day of the same factory overlap THE SYSTEM SHALL return 400.
4. WHEN a break does not lie entirely inside its shift THE SYSTEM SHALL return 400.
5. WHEN a client sends `GET /api/factories/{id}` THE SYSTEM SHALL include the calendar with shifts ordered by day of week then start time, each with its breaks.
6. WHEN a factory is created without a calendar THE SYSTEM SHALL create it with no shifts; simulation validation (spec 07) rejects an empty calendar when there is work to do.

### Requirement 3: Factory overview data

**User Story:** As a factory owner, I want the dashboard to load real factory configuration, so that I am not
looking at placeholder values.

#### Acceptance Criteria

1. WHEN a client sends `GET /api/factories/{id}` THE SYSTEM SHALL return name, description, time zone, currency, `demo` flag, calendar, and counts of workflows, resource pools and production orders read from the database.
2. WHEN the factory is the seeded demonstration factory THE SYSTEM SHALL return `demo: true` so the UI can label all of its data as synthetic.

### Requirement 4: Demonstration factory seed

**User Story:** As a hackathon judge, I want a fully configured factory available on first start, so that I
can explore the product without configuring anything.

#### Acceptance Criteria

1. WHEN the application starts with `realitycheck.demo.seed-on-startup=true` and the `factory` table is empty THE SYSTEM SHALL create "Surat Diamond Works" (time zone `Asia/Kolkata`, currency `INR`, `demo=true`) with a Monday-Saturday 09:00-18:00 calendar and a 13:00-14:00 break on each working day.
2. WHEN the application starts and at least one factory exists THE SYSTEM SHALL NOT run the seed.
3. WHEN the seed runs twice against empty databases THE SYSTEM SHALL produce identical rows (same UUIDs, same timestamps), because all ids are derived from fixed names and all timestamps from a fixed anchor instant.
4. WHEN any section of the seed fails THE SYSTEM SHALL roll back the whole seed and log the failure without preventing application start-up.

### Requirement 5: Demo reset

**User Story:** As a presenter, I want to reset the demo to its initial state, so that each demonstration
starts from the same data.

#### Acceptance Criteria

1. WHEN `realitycheck.demo.reset-enabled=true` and a client sends `POST /api/demo/reset` THE SYSTEM SHALL remove all data and re-run the seed in one transaction, and return 200 with the new factory id.
2. WHEN `realitycheck.demo.reset-enabled=false` THE SYSTEM SHALL return 404 for `POST /api/demo/reset`.
3. WHEN a simulation run is PENDING or RUNNING THE SYSTEM SHALL refuse the reset with 409.
