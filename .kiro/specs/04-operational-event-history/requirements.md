# 04 Operational Event History — Requirements

## Introduction

This spec covers production orders, their batches, and the append-only log of operational events that
records what happened on the shop floor. The event log is the source of truth: the status and current stage
shown on orders and batches are projections of it, and the factory state at any past instant is rebuilt by
replaying it (spec 05).

An **order** is the customer commitment (number, priority, due date). A **batch** is the physical parcel that
moves through stages. One order has one or more batches. Stone count and carat weight are separate
quantities and are never converted into each other.

## Requirements

### Requirement 1: Production orders and batches

**User Story:** As a production planner, I want to register an order with its batches, so that the work
enters the factory's history.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/orders` with order number, priority, optional due date and one or more batches (stone count, rough carats) THE SYSTEM SHALL create the order, its batches, one `ORDER_CREATED` event, and one `STAGE_QUEUED` event per batch at the first stage, in one transaction, and return 201.
2. WHEN the order number already exists in the factory THE SYSTEM SHALL return 409.
3. WHEN a batch has `stoneCount` below 1 or `roughCarats` not greater than zero THE SYSTEM SHALL return 400.
4. WHEN `priority` is outside 1-5 THE SYSTEM SHALL return 400. A higher number means higher priority.
5. WHEN the factory has no ACTIVE workflow THE SYSTEM SHALL return 422.
6. WHEN a client sends `GET /api/orders?factoryId=&status=` THE SYSTEM SHALL return orders in the list envelope, newest first, each with batch count, total stones, total rough carats, status, due date and a `delayed` flag.
7. WHEN a client sends `GET /api/orders/{id}` THE SYSTEM SHALL return the order with its batches (current stage, status, current stones and carats) and its events in chronological order.

### Requirement 2: Append-only events

**User Story:** As a factory owner, I want recorded history to be tamper-evident, so that scenarios branch
from what really happened.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/events` with a valid event THE SYSTEM SHALL append it and return 201 with the stored event.
2. WHEN any SQL `UPDATE` or `DELETE` is attempted on `operational_event` THE SYSTEM SHALL reject it with a database trigger error.
3. WHEN an event is stored THE SYSTEM SHALL record `occurred_at` (when it happened, supplied by the client), `created_at` (when it was recorded) and a monotonically increasing `seq`.
4. WHEN `occurredAt` is in the future relative to the server clock by more than five minutes THE SYSTEM SHALL return 400.
5. WHEN a simulation runs THE SYSTEM SHALL NOT write to `operational_event`, `production_order` or `production_batch`.

### Requirement 3: Event validation

**User Story:** As a supervisor, I want impossible events rejected, so that the history stays consistent.

#### Acceptance Criteria

1. WHEN an event references an order, batch, stage or resource THE SYSTEM SHALL verify each exists and belongs to the event's factory, else return 400.
2. WHEN a `STAGE_STARTED` event arrives for a batch that is not queued at that stage THE SYSTEM SHALL return 422.
3. WHEN a `STAGE_COMPLETED` event arrives for a batch that is not in progress at that stage THE SYSTEM SHALL return 422.
4. WHEN a `STAGE_COMPLETED` event arrives THE SYSTEM SHALL require output `stoneQuantity` and `caratQuantity`, each greater than zero and not greater than the batch's current quantities, else return 400.
5. WHEN an event's `occurredAt` is earlier than the latest event already recorded for the same batch THE SYSTEM SHALL return 422.
6. WHEN a `STAGE_COMPLETED` event is accepted for a stage that is not the last THE SYSTEM SHALL append a `STAGE_QUEUED` event for the next stage at the same `occurredAt` in the same transaction.
7. WHEN a `STAGE_COMPLETED` event is accepted for the last stage of an order's final unfinished batch THE SYSTEM SHALL append `ORDER_COMPLETED` in the same transaction.
8. WHEN an `ORDER_DISPATCHED` event arrives for an order that is not completed THE SYSTEM SHALL return 422.

### Requirement 4: Corrections

**User Story:** As a supervisor, I want to correct a wrongly recorded event without erasing it, so that the
audit trail is preserved.

#### Acceptance Criteria

1. WHEN a client sends a `CORRECTION` event with `correctsEventId` and a replacement for `occurredAt`, `stoneQuantity`, `caratQuantity` or `durationSeconds` THE SYSTEM SHALL append it and leave the original row unchanged.
2. WHEN `correctsEventId` is unknown, belongs to another factory, or is itself a `CORRECTION` THE SYSTEM SHALL return 400.
3. WHEN an event has one or more corrections THE SYSTEM SHALL treat the latest correction (by `seq`) as the effective values for projections, the timeline and state reconstruction.
4. WHEN a correction with `payload.voided = true` is appended THE SYSTEM SHALL exclude the original event from projections and reconstruction, provided the resulting event sequence for the batch is still valid; otherwise return 422.
5. WHEN a correction is accepted THE SYSTEM SHALL rebuild the affected batch and order projections from the effective event sequence in the same transaction.

### Requirement 5: Status projections

**User Story:** As a production manager, I want each order's and batch's current status, so that I can see
what is pending, active, done or late.

#### Acceptance Criteria

1. WHEN an event is appended THE SYSTEM SHALL update the batch's `status`, `current_stage_id`, `current_stones` and `current_carats`, and the order's `status`, in the same transaction.
2. WHEN all of an order's batches have completed the last stage THE SYSTEM SHALL set the order status to `COMPLETED`, and to `DISPATCHED` after `ORDER_DISPATCHED`.
3. WHEN an order has a due date and is not completed by it, or was completed after it THE SYSTEM SHALL report `delayed: true`.
4. WHEN projections are rebuilt from events for any batch THE SYSTEM SHALL arrive at exactly the stored projection values.

### Requirement 6: Event queries

**User Story:** As a user, I want to list events with filters, so that I can inspect history.

#### Acceptance Criteria

1. WHEN a client sends `GET /api/events?factoryId=&from=&to=&orderId=&batchId=&stageId=&type=` THE SYSTEM SHALL return matching events in the list envelope ordered by `occurredAt` then `seq`.
2. WHEN `size` exceeds 200 THE SYSTEM SHALL cap it at 200.

### Requirement 7: Demonstration history

**User Story:** As a judge, I want a believable production history, so that the timeline and the time machine
have something to branch from.

#### Acceptance Criteria

1. WHEN the demo seed runs THE SYSTEM SHALL create at least twelve orders with synthetic quantities, priorities and due dates, spread over at least two working weeks before the seed's "now", in a mix of completed, in-progress, queued and not-yet-started states.
2. WHEN the demo seed runs THE SYSTEM SHALL generate every event through the same validation path as the API, so the seeded history satisfies Requirement 3, and every event's `occurredAt` SHALL fall inside factory working time.
3. WHEN the demo seed runs THE SYSTEM SHALL include at least one `CORRECTION` and one `RESOURCE_UNAVAILABLE` event.
