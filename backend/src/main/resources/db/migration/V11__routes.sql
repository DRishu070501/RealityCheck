-- Spec 11, task 9: routes. A workflow may define routes, each an ordered list of its stages with the share of
-- batches that visit each one ([{"code", "name", "steps": [{"stageCode", "visitPercent"}]}]); null when it has
-- none, and then every batch visits every stage in sequence, as before. A batch may follow one of them.
ALTER TABLE workflow
    ADD COLUMN routes jsonb;

ALTER TABLE production_batch
    ADD COLUMN route_code varchar(40);

-- A scenario change may scale a stage's measured waiting times (STAGE_DELAY).
ALTER TABLE scenario_change DROP CONSTRAINT scenario_change_change_type_check;
ALTER TABLE scenario_change ADD CONSTRAINT scenario_change_change_type_check CHECK (change_type IN (
    'RESOURCE_CAPACITY', 'RESOURCE_UNAVAILABLE', 'RESOURCE_DOWNTIME', 'DEMAND_VOLUME', 'PROCESSING_RATE',
    'WORKING_SCHEDULE', 'STAGE_DELAY'));
