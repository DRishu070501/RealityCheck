-- Factory and its weekly working calendar.

CREATE TABLE factory (
    id            uuid PRIMARY KEY,
    name          varchar(120) NOT NULL,
    description   text,
    time_zone     varchar(64)  NOT NULL,
    currency_code varchar(3)   NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    demo          boolean      NOT NULL DEFAULT false,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    CONSTRAINT factory_name_unique UNIQUE (name)
);

-- day_of_week is ISO: 1 = Monday .. 7 = Sunday. Times are local to the factory's time zone.
CREATE TABLE working_shift (
    id          uuid PRIMARY KEY,
    factory_id  uuid        NOT NULL REFERENCES factory (id) ON DELETE CASCADE,
    day_of_week integer     NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time  time        NOT NULL,
    end_time    time        NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT working_shift_ends_after_start CHECK (end_time > start_time),
    CONSTRAINT working_shift_unique_start UNIQUE (factory_id, day_of_week, start_time)
);

CREATE TABLE calendar_break (
    id               uuid PRIMARY KEY,
    working_shift_id uuid        NOT NULL REFERENCES working_shift (id) ON DELETE CASCADE,
    start_time       time        NOT NULL,
    end_time         time        NOT NULL,
    created_at       timestamptz NOT NULL,
    CONSTRAINT calendar_break_ends_after_start CHECK (end_time > start_time)
);

CREATE INDEX calendar_break_shift_idx ON calendar_break (working_shift_id);
