-- Workflow, its ordered stages, and what each stage needs per processing slot.

CREATE TABLE workflow (
    id          uuid PRIMARY KEY,
    factory_id  uuid         NOT NULL REFERENCES factory (id),
    name        varchar(120) NOT NULL,
    description text,
    status      varchar(16)  NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT workflow_name_unique UNIQUE (factory_id, name)
);

CREATE UNIQUE INDEX workflow_one_active_per_factory ON workflow (factory_id) WHERE status = 'ACTIVE';

CREATE TABLE workflow_stage (
    id                 uuid PRIMARY KEY,
    workflow_id        uuid           NOT NULL REFERENCES workflow (id) ON DELETE CASCADE,
    code               varchar(40)    NOT NULL,
    name               varchar(120)   NOT NULL,
    description        text,
    sequence           integer        NOT NULL CHECK (sequence >= 1),
    stones_per_hour    numeric(12, 4) NOT NULL CHECK (stones_per_hour > 0),          -- per processing slot
    setup_seconds      bigint         NOT NULL CHECK (setup_seconds >= 0),           -- per job
    stone_yield        numeric(7, 6)  NOT NULL CHECK (stone_yield > 0 AND stone_yield <= 1),
    carat_yield        numeric(7, 6)  NOT NULL CHECK (carat_yield > 0 AND carat_yield <= 1),
    fixed_cost_per_job numeric(14, 2) NOT NULL DEFAULT 0 CHECK (fixed_cost_per_job >= 0),
    version            bigint         NOT NULL DEFAULT 0,
    created_at         timestamptz    NOT NULL,
    updated_at         timestamptz    NOT NULL,
    CONSTRAINT workflow_stage_sequence_unique UNIQUE (workflow_id, sequence),
    CONSTRAINT workflow_stage_code_unique UNIQUE (workflow_id, code)
);

-- One processing slot of the stage needs units_per_slot units of this pool.
CREATE TABLE stage_requirement (
    id             uuid PRIMARY KEY,
    stage_id       uuid        NOT NULL REFERENCES workflow_stage (id) ON DELETE CASCADE,
    resource_id    uuid        NOT NULL REFERENCES resource (id),
    units_per_slot integer     NOT NULL CHECK (units_per_slot >= 1),
    created_at     timestamptz NOT NULL,
    CONSTRAINT stage_requirement_unique_pool UNIQUE (stage_id, resource_id)
);

CREATE INDEX stage_requirement_resource_idx ON stage_requirement (resource_id);
