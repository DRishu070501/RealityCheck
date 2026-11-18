-- Spec 11, task 10: backtests. A backtest simulates a factory from past fork dates with no changes and
-- compares each simulation with what recorded history shows over the same window. Rows are written once.
CREATE TABLE backtest (
    id                uuid PRIMARY KEY,
    factory_id        uuid        NOT NULL REFERENCES factory (id),
    algorithm_version varchar(20) NOT NULL,
    created_at        timestamptz NOT NULL
);

CREATE INDEX backtest_factory_created_idx ON backtest (factory_id, created_at DESC);

CREATE TABLE backtest_fork (
    id          uuid PRIMARY KEY,
    backtest_id uuid        NOT NULL REFERENCES backtest (id) ON DELETE CASCADE,
    ordinal     int         NOT NULL,
    fork_at     timestamptz NOT NULL,
    result      jsonb       NOT NULL,  -- the comparison: finished, median lead time, work in progress per stage
    created_at  timestamptz NOT NULL,
    CONSTRAINT backtest_fork_ordinal_unique UNIQUE (backtest_id, ordinal)
);
