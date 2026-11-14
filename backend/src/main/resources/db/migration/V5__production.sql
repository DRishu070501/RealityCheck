-- Production orders, their batches, and the append-only operational event history.

CREATE TABLE production_order (
    id           uuid PRIMARY KEY,
    factory_id   uuid         NOT NULL REFERENCES factory (id),
    order_number varchar(40)  NOT NULL,
    customer_ref varchar(120),
    priority     integer      NOT NULL CHECK (priority BETWEEN 1 AND 5),   -- 5 = most urgent
    due_at       timestamptz,
    placed_at    timestamptz  NOT NULL,
    -- status and completed_at are projections of the event history, kept for fast reads.
    status       varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'DISPATCHED')),
    completed_at timestamptz,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT production_order_number_unique UNIQUE (factory_id, order_number)
);

CREATE INDEX production_order_factory_status_idx ON production_order (factory_id, status);
CREATE INDEX production_order_factory_due_idx ON production_order (factory_id, due_at);

-- A batch is the physical parcel that moves through the stages. Stones and carats are separate
-- quantities: stone_count is a whole number of stones, rough_carats their total weight.
CREATE TABLE production_batch (
    id               uuid PRIMARY KEY,
    order_id         uuid           NOT NULL REFERENCES production_order (id),
    batch_number     integer        NOT NULL CHECK (batch_number >= 1),
    stone_count      integer        NOT NULL CHECK (stone_count >= 1),
    rough_carats     numeric(14, 4) NOT NULL CHECK (rough_carats > 0),
    -- Projections of the event history: where the batch is now and what it currently holds.
    current_stones   numeric(14, 4) NOT NULL CHECK (current_stones >= 0),
    current_carats   numeric(14, 4) NOT NULL CHECK (current_carats >= 0),
    current_stage_id uuid REFERENCES workflow_stage (id),                  -- null once finished
    status           varchar(16)    NOT NULL CHECK (status IN ('QUEUED', 'IN_PROGRESS', 'COMPLETED')),
    version          bigint         NOT NULL DEFAULT 0,
    created_at       timestamptz    NOT NULL,
    updated_at       timestamptz    NOT NULL,
    CONSTRAINT production_batch_number_unique UNIQUE (order_id, batch_number)
);

CREATE INDEX production_batch_stage_idx ON production_batch (current_stage_id);

-- What happened, in the order it was recorded. Rows are never changed or removed: a mistake is
-- fixed by a CORRECTION row that points at the event it replaces values of, or voids.
CREATE TABLE operational_event (
    id                uuid PRIMARY KEY,
    seq               bigint GENERATED ALWAYS AS IDENTITY UNIQUE,         -- recording order
    factory_id        uuid        NOT NULL REFERENCES factory (id),
    order_id          uuid REFERENCES production_order (id),
    batch_id          uuid REFERENCES production_batch (id),
    stage_id          uuid REFERENCES workflow_stage (id),
    resource_id       uuid REFERENCES resource (id),
    event_type        varchar(32) NOT NULL CHECK (event_type IN (
        'ORDER_CREATED', 'STAGE_QUEUED', 'STAGE_STARTED', 'STAGE_COMPLETED',
        'RESOURCE_UNAVAILABLE', 'ORDER_COMPLETED', 'ORDER_DISPATCHED', 'CORRECTION')),
    occurred_at       timestamptz NOT NULL,                                -- when it happened
    stone_quantity    numeric(14, 4) CHECK (stone_quantity IS NULL OR stone_quantity >= 0),
    carat_quantity    numeric(14, 4) CHECK (carat_quantity IS NULL OR carat_quantity >= 0),
    duration_seconds  bigint CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    payload           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    corrects_event_id uuid REFERENCES operational_event (id),
    voids             boolean     NOT NULL DEFAULT false,                  -- a correction that cancels its target
    created_at        timestamptz NOT NULL,                                -- when it was recorded
    CONSTRAINT operational_event_correction_has_target
        CHECK ((event_type = 'CORRECTION') = (corrects_event_id IS NOT NULL)),
    CONSTRAINT operational_event_only_corrections_void
        CHECK (NOT voids OR event_type = 'CORRECTION')
);

CREATE INDEX operational_event_factory_time_idx ON operational_event (factory_id, occurred_at, seq);
CREATE INDEX operational_event_batch_idx ON operational_event (batch_id, seq);
CREATE INDEX operational_event_order_idx ON operational_event (order_id, occurred_at, seq);
CREATE INDEX operational_event_stage_idx ON operational_event (stage_id, occurred_at);
CREATE INDEX operational_event_corrects_idx ON operational_event (corrects_event_id)
    WHERE corrects_event_id IS NOT NULL;

-- Enforced in the database, not by convention: no code path can rewrite history.
-- TRUNCATE is deliberately not blocked; only the demonstration reset uses it.
CREATE TRIGGER operational_event_append_only
    BEFORE UPDATE OR DELETE ON operational_event
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
