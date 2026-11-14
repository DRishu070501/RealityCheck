-- Resource pools and their dated capacity changes.
-- A resource row is a pool of interchangeable units (machines of one type, or employees with one skill).

CREATE TABLE resource (
    id          uuid PRIMARY KEY,
    factory_id  uuid          NOT NULL REFERENCES factory (id),
    kind        varchar(16)   NOT NULL CHECK (kind IN ('MACHINE', 'EMPLOYEE')),
    category    varchar(60)   NOT NULL,
    name        varchar(120)  NOT NULL,
    capacity    integer       NOT NULL CHECK (capacity >= 0),
    hourly_cost numeric(12, 2) NOT NULL CHECK (hourly_cost >= 0),   -- per unit, per hour
    status      varchar(16)   NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version     bigint        NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    CONSTRAINT resource_name_unique UNIQUE (factory_id, name)
);

CREATE INDEX resource_factory_kind_idx ON resource (factory_id, kind);

-- Half-open window [starts_at, ends_at). A negative delta is units unavailable (downtime, absence).
-- Windows are never edited; a mistake is countered by an opposite delta over the same period.
CREATE TABLE resource_availability (
    id             uuid PRIMARY KEY,
    resource_id    uuid         NOT NULL REFERENCES resource (id) ON DELETE CASCADE,
    starts_at      timestamptz  NOT NULL,
    ends_at        timestamptz  NOT NULL,
    capacity_delta integer      NOT NULL CHECK (capacity_delta <> 0),
    reason         varchar(200) NOT NULL,
    created_at     timestamptz  NOT NULL,
    CONSTRAINT resource_availability_ends_after_start CHECK (ends_at > starts_at)
);

CREATE INDEX resource_availability_window_idx ON resource_availability (resource_id, starts_at, ends_at);
