-- Scenarios: a named "what if" forked from an instant of recorded history, with an ordered list of
-- changes. Scenarios never touch live configuration or history; changes are applied to a copy (the
-- input snapshot) when a scenario is validated or simulated.

CREATE TABLE scenario (
    id                    uuid PRIMARY KEY,
    factory_id            uuid         NOT NULL REFERENCES factory (id),
    name                  varchar(120) NOT NULL,
    description           text,
    parent_scenario_id    uuid         REFERENCES scenario (id) ON DELETE SET NULL,
    fork_at               timestamptz  NOT NULL,
    include_future_orders boolean      NOT NULL DEFAULT true,
    version               bigint       NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    CONSTRAINT scenario_name_unique UNIQUE (factory_id, name)
);

CREATE INDEX scenario_factory_idx ON scenario (factory_id);
CREATE INDEX scenario_parent_idx ON scenario (parent_scenario_id);

-- Ordinals are 1..n without gaps. The unique constraint is deferred so that renumbering after a
-- delete can happen row by row inside one transaction.
CREATE TABLE scenario_change (
    id          uuid PRIMARY KEY,
    scenario_id uuid        NOT NULL REFERENCES scenario (id) ON DELETE CASCADE,
    ordinal     integer     NOT NULL CHECK (ordinal >= 1),
    change_type varchar(32) NOT NULL CHECK (change_type IN
                ('RESOURCE_CAPACITY', 'RESOURCE_UNAVAILABLE', 'RESOURCE_DOWNTIME',
                 'DEMAND_VOLUME', 'PROCESSING_RATE', 'WORKING_SCHEDULE')),
    payload     jsonb       NOT NULL,
    inherited   boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT scenario_change_ordinal_unique UNIQUE (scenario_id, ordinal) DEFERRABLE INITIALLY DEFERRED
);
