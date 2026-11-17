-- Spec 11, task 8: how long a batch stays at a working stage after processing, with its units already
-- released: the work is done but the packet has not yet moved on. Ten calendar durations in seconds,
-- ascending; one per batch. Optional; only working stages may have it.
ALTER TABLE workflow_stage
    ADD COLUMN handling_deciles jsonb,
    ADD CONSTRAINT workflow_stage_handling_only_when_working
        CHECK (handling_deciles IS NULL OR kind = 'WORKING');
