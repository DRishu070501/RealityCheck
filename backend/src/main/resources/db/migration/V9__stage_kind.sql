-- Spec 11: a stage either works batches on pool capacity (WORKING, as before) or holds them for a measured
-- delay with no capacity limit: PARKING (deliberately parked, excluded from manufacturing lead time),
-- WAITING (part of manufacturing, such as waiting for dispatch) or JOB_WORK (sent to an outside job worker).
-- delay_deciles: ten calendar durations in seconds, ascending, measured for the stage; one per batch.
ALTER TABLE workflow_stage
    ADD COLUMN kind          varchar(16) NOT NULL DEFAULT 'WORKING'
        CHECK (kind IN ('WORKING', 'PARKING', 'WAITING', 'JOB_WORK')),
    ADD COLUMN delay_deciles jsonb,
    ADD CONSTRAINT workflow_stage_delay_matches_kind
        CHECK ((kind = 'WORKING') = (delay_deciles IS NULL));
