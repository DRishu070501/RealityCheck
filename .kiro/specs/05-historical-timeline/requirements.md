# 05 Historical Timeline — Requirements

## Introduction

The timeline is the read side of the event history (spec 04) and the entry point to the operational time
machine. It lets a user browse what happened, pick an instant, see the factory state reconstructed at that
instant, and start a scenario from it (spec 06).

State reconstruction rebuilds **work in progress** by replaying effective events up to the chosen instant.
Resource and stage configuration is not reconstructed; it is captured when a snapshot is taken
(architecture.md, "Configuration is captured, not reconstructed").

## Requirements

### Requirement 1: Timeline query

**User Story:** As a production manager, I want a chronological view of factory events with filters, so that
I can understand what happened and when.

#### Acceptance Criteria

1. WHEN a client sends `GET /api/timeline?factoryId=&from=&to=` THE SYSTEM SHALL return effective events in the range ordered by `occurredAt` then `seq`, in the list envelope, each enriched with order number, batch number, stage code and stage name.
2. WHEN `stageId`, `orderId` or `type` filters are supplied THE SYSTEM SHALL return only matching events; filters combine with AND.
3. WHEN an event has been corrected THE SYSTEM SHALL return its effective values with `corrected: true` and the original values under `original`; voided events are omitted unless `includeVoided=true`.
4. WHEN `from` and `to` are omitted THE SYSTEM SHALL default to the full recorded range of the factory.
5. WHEN `order=desc` is supplied THE SYSTEM SHALL return newest first.

### Requirement 2: Timeline summary

**User Story:** As a user, I want an overview of activity over time, so that I can navigate to interesting
periods and choose a fork point.

#### Acceptance Criteria

1. WHEN a client sends `GET /api/timeline/summary?factoryId=&bucket=` with `bucket` of `HOUR` or `DAY` THE SYSTEM SHALL return, per bucket in the factory time zone, the count of events by type.
2. WHEN a factory has no events THE SYSTEM SHALL return an empty bucket list and `firstEventAt` and `lastEventAt` as `null`.
3. WHEN the summary is returned THE SYSTEM SHALL include `firstEventAt` and `lastEventAt`, which bound the selectable fork range.

### Requirement 3: State reconstruction

**User Story:** As a user of the time machine, I want to see the factory as it was at a chosen instant, so
that I know what my scenario starts from.

#### Acceptance Criteria

1. WHEN a client sends `GET /api/factories/{id}/state?at=` THE SYSTEM SHALL replay all effective events with `occurredAt <= at` and return, for every batch of every order placed at or before `at`: status, stage, input stones and carats at that stage, queue-entry time, and start time when in progress.
2. WHEN a batch has `STAGE_STARTED` without `STAGE_COMPLETED` at `at` THE SYSTEM SHALL report it as `IN_PROGRESS` at that stage with its recorded start time.
3. WHEN `at` is omitted THE SYSTEM SHALL use the current time.
4. WHEN `at` is earlier than the factory's first event THE SYSTEM SHALL return an empty state, not an error.
5. WHEN the state is returned THE SYSTEM SHALL include per-stage counts (queued, in progress, completed-through), order counts by status, and active `RESOURCE_UNAVAILABLE` windows at `at`.
6. WHEN the state is returned THE SYSTEM SHALL include `baseStateHash`, a SHA-256 of the canonical state, so that two reconstructions of the same history at the same instant are verifiably identical.
7. WHEN state is reconstructed THE SYSTEM SHALL NOT modify any table.
8. WHEN a correction recorded later changes an event that occurred before `at` THE SYSTEM SHALL use the corrected values, because corrections describe what actually happened.

### Requirement 4: Reconstruction correctness

**User Story:** As an engineer, I want reconstruction at "now" to agree with the stored projections, so that
the two views of the factory cannot drift apart.

#### Acceptance Criteria

1. WHEN state is reconstructed at an instant after the last event THE SYSTEM SHALL produce batch statuses, stages and quantities equal to the stored `production_batch` projections.
2. WHEN state is reconstructed at two instants `t1 < t2` THE SYSTEM SHALL never show a batch at an earlier stage at `t2` than at `t1`.

### Requirement 5: Live overview figures

**User Story:** As a factory owner, I want the overview page to show current counts and recent activity from
real data, so that nothing on the dashboard is invented.

#### Acceptance Criteria

1. WHEN a client sends `GET /api/factories/{id}/overview` THE SYSTEM SHALL return active, pending, completed and delayed order counts, per-stage queue and in-progress counts, units in use per pool, stones completed per day for the last 14 days of recorded history, and the ten most recent events, all with `source: "ACTUAL"`.
2. WHEN a figure cannot be derived from recorded data (for example utilization over a period) THE SYSTEM SHALL omit it from this response; the UI obtains it from the latest baseline simulation run and labels it `SIMULATED`.

### Requirement 6: Fork from the timeline

**User Story:** As a user, I want to create a scenario from a selected instant, so that I can ask "what if"
from that point.

#### Acceptance Criteria

1. WHEN the user selects an instant on the timeline THE SYSTEM SHALL show the reconstructed state for that instant before the scenario is created.
2. WHEN the user confirms THE SYSTEM SHALL create a scenario through `POST /api/scenarios` (spec 06) with `forkAt` set to the selected instant.
3. WHEN the selected instant is outside `[firstEventAt, now]` THE SYSTEM SHALL prevent scenario creation and explain why.
