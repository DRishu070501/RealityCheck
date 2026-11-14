-- Stored simulation runs and their results.
--
-- A run holds the complete input it was computed from (input_snapshot), so its results can be
-- reproduced and are unaffected by later edits to configuration or to the scenario. The input
-- columns can never change once written; result rows are written once, in the same transaction
-- that marks the run COMPLETED, and never updated.

CREATE TABLE simulation_run (
    id                 uuid PRIMARY KEY,
    scenario_id        uuid         NOT NULL REFERENCES scenario (id),
    factory_id         uuid         NOT NULL REFERENCES factory (id),
    status             varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    idempotency_key    varchar(100),
    fork_at            timestamptz  NOT NULL,
    input_snapshot     jsonb        NOT NULL,
    input_hash         varchar(64)  NOT NULL,
    base_state_hash    varchar(64)  NOT NULL,
    algorithm_version  varchar(20)  NOT NULL,
    random_seed        bigint,                    -- always null: the engine is deterministic
    termination_reason varchar(24),
    simulated_end_at   timestamptz,
    events_processed   bigint,
    summary            jsonb,                     -- summary metrics, orders, bottleneck, unfinished
    failure_message    varchar(500),
    submitted_at       timestamptz  NOT NULL,
    started_at         timestamptz,
    finished_at        timestamptz,
    created_at         timestamptz  NOT NULL,
    CONSTRAINT simulation_run_idempotency_unique UNIQUE (scenario_id, idempotency_key)
);

CREATE INDEX simulation_run_scenario_submitted_idx ON simulation_run (scenario_id, submitted_at DESC);
CREATE INDEX simulation_run_scenario_hash_idx ON simulation_run (scenario_id, input_hash);
CREATE INDEX simulation_run_active_idx ON simulation_run (status) WHERE status IN ('PENDING', 'RUNNING');

CREATE FUNCTION protect_simulation_run_input() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.input_snapshot IS DISTINCT FROM OLD.input_snapshot
        OR NEW.input_hash IS DISTINCT FROM OLD.input_hash
        OR NEW.base_state_hash IS DISTINCT FROM OLD.base_state_hash
        OR NEW.algorithm_version IS DISTINCT FROM OLD.algorithm_version
        OR NEW.fork_at IS DISTINCT FROM OLD.fork_at
        OR NEW.scenario_id IS DISTINCT FROM OLD.scenario_id
        OR NEW.factory_id IS DISTINCT FROM OLD.factory_id THEN
        RAISE EXCEPTION 'simulation_run inputs are immutable'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER simulation_run_input_immutable
    BEFORE UPDATE ON simulation_run
    FOR EACH ROW EXECUTE FUNCTION protect_simulation_run_input();

-- Result tables keep stage and resource ids without foreign keys and copy names from the snapshot:
-- results stay readable and unchanged even if the configuration is later edited.

CREATE TABLE simulation_stage_result (
    id                         uuid PRIMARY KEY,
    run_id                     uuid           NOT NULL REFERENCES simulation_run (id) ON DELETE CASCADE,
    stage_id                   uuid           NOT NULL,
    stage_code                 varchar(40)    NOT NULL,
    stage_name                 varchar(120)   NOT NULL,
    sequence                   integer        NOT NULL,
    jobs_completed             integer        NOT NULL,
    input_stones               numeric(14, 4) NOT NULL,
    output_stones              numeric(14, 4) NOT NULL,
    loss_stones                numeric(14, 4) NOT NULL,
    input_carats               numeric(14, 4) NOT NULL,
    output_carats              numeric(14, 4) NOT NULL,
    loss_carats                numeric(14, 4) NOT NULL,
    processing_seconds         bigint         NOT NULL,
    waiting_working_seconds    bigint         NOT NULL,
    waiting_calendar_seconds   bigint         NOT NULL,
    mean_wait_working_seconds  numeric(16, 4) NOT NULL,
    mean_stage_seconds         numeric(16, 4) NOT NULL,
    max_stage_seconds          bigint         NOT NULL,
    mean_queue_length          numeric(12, 4) NOT NULL,
    max_queue_length           integer        NOT NULL,
    throughput_stones_per_hour numeric(14, 4),
    fixed_cost                 numeric(16, 2) NOT NULL,
    limiting_resource_id       uuid,
    limiting_resource_name     varchar(120),
    limiting_utilization       numeric(7, 6),
    utilization_signal         numeric(7, 6)  NOT NULL,
    wait_signal                numeric(7, 6)  NOT NULL,
    queue_signal               numeric(7, 6)  NOT NULL,
    saturation_signal          numeric(7, 6)  NOT NULL,
    blocked_share              numeric(7, 6)  NOT NULL,
    bottleneck_score           numeric(7, 6)  NOT NULL,
    primary_bottleneck         boolean        NOT NULL,
    created_at                 timestamptz    NOT NULL,
    CONSTRAINT simulation_stage_result_unique UNIQUE (run_id, sequence)
);

CREATE TABLE simulation_resource_result (
    id                      uuid PRIMARY KEY,
    run_id                  uuid           NOT NULL REFERENCES simulation_run (id) ON DELETE CASCADE,
    resource_id             uuid           NOT NULL,
    kind                    varchar(16)    NOT NULL,
    name                    varchar(120)   NOT NULL,
    capacity                integer        NOT NULL,
    busy_unit_seconds       bigint         NOT NULL,
    available_unit_seconds  bigint         NOT NULL,
    idle_unit_seconds       bigint         NOT NULL,
    utilization             numeric(7, 6),
    utilization_null_reason varchar(32),
    overcommitted           boolean        NOT NULL,
    cost                    numeric(16, 2) NOT NULL,
    created_at              timestamptz    NOT NULL,
    CONSTRAINT simulation_resource_result_unique UNIQUE (run_id, resource_id)
);

CREATE TABLE simulation_event_result (
    id             uuid PRIMARY KEY,
    run_id         uuid           NOT NULL REFERENCES simulation_run (id) ON DELETE CASCADE,
    seq            bigint         NOT NULL,
    occurred_at    timestamptz    NOT NULL,
    event_type     varchar(24)    NOT NULL,
    batch_id       uuid,
    order_id       uuid,
    stage_id       uuid,
    resource_id    uuid,
    stone_quantity numeric(14, 4),
    carat_quantity numeric(14, 4),
    capacity       integer,
    CONSTRAINT simulation_event_result_unique UNIQUE (run_id, seq)
);

CREATE INDEX simulation_event_result_stage_idx ON simulation_event_result (run_id, stage_id);
CREATE INDEX simulation_event_result_order_idx ON simulation_event_result (run_id, order_id);

CREATE TRIGGER simulation_stage_result_append_only
    BEFORE UPDATE ON simulation_stage_result
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER simulation_resource_result_append_only
    BEFORE UPDATE ON simulation_resource_result
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER simulation_event_result_append_only
    BEFORE UPDATE ON simulation_event_result
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
