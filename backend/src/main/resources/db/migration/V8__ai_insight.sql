-- AI insights: explanations of stored simulation results. The ai module writes only these two
-- tables; simulation results are never changed by it. Insights are immutable: regenerating one
-- inserts a new row.

create table simulation_insight (
    id               uuid primary key,
    run_id           uuid         not null references simulation_run (id) on delete cascade,
    baseline_run_id  uuid references simulation_run (id) on delete cascade,
    insight_type     varchar(24)  not null check (insight_type in
                     ('EXPLANATION', 'BOTTLENECK', 'RECOMMENDATIONS', 'DELAY_ANALYSIS',
                      'COMPARISON_SUMMARY', 'EXECUTIVE_REPORT', 'ANSWER')),
    question         varchar(500),
    provider         varchar(40)  not null,
    model            varchar(80),
    fallback         boolean      not null default false,
    fallback_reason  varchar(40),
    evidence_hash    varchar(64)  not null,
    content          jsonb        not null,
    input_tokens     integer,
    output_tokens    integer,
    created_at       timestamptz  not null,
    constraint simulation_insight_question_only_for_answers
        check ((insight_type = 'ANSWER') = (question is not null)),
    constraint simulation_insight_fallback_has_reason
        check (fallback = (fallback_reason is not null))
);

create index simulation_insight_run_created on simulation_insight (run_id, created_at desc, id desc);
create index simulation_insight_cache on simulation_insight (run_id, baseline_run_id, insight_type, evidence_hash);

create trigger simulation_insight_append_only before update on simulation_insight
    for each row execute function reject_modification();

-- The evidence items an insight cites, as they were when it was generated.
create table insight_evidence (
    id            uuid primary key,
    insight_id    uuid          not null references simulation_insight (id) on delete cascade,
    metric_key    varchar(160)  not null,
    label         varchar(200)  not null,
    value_numeric numeric(24, 6),
    value_text    varchar(400),
    unit          varchar(40),
    display       jsonb         not null default '[]'::jsonb,
    source_run_id uuid          not null,
    stage_id      uuid,
    resource_id   uuid,
    created_at    timestamptz   not null
);

create index insight_evidence_insight on insight_evidence (insight_id);

create trigger insight_evidence_append_only before update on insight_evidence
    for each row execute function reject_modification();
