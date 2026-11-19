package com.realitycheck.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.ai.api.AiDtos;
import com.realitycheck.ai.api.AiDtos.EvidenceItemResponse;
import com.realitycheck.ai.api.AiDtos.InsightResponse;
import com.realitycheck.ai.domain.InsightContent;
import com.realitycheck.ai.domain.InsightType;
import com.realitycheck.ai.evidence.Evidence.EvidenceItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and reads insights. The only writer in the {@code ai} module, and it writes only
 * {@code simulation_insight} and {@code insight_evidence}; both are append-only.
 */
@Repository
public class InsightStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public InsightStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** What to store; {@code evidence} holds only the items the content cites. */
    public record NewInsight(UUID runId, UUID baselineRunId, InsightType type, String question, String provider,
            String model, boolean fallback, String fallbackReason, String evidenceHash, InsightContent content,
            Integer inputTokens, Integer outputTokens, List<EvidenceItem> evidence, Instant createdAt) {
    }

    @Transactional
    public InsightResponse save(NewInsight n) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into simulation_insight (id, run_id, baseline_run_id, insight_type, question, provider, model,
                    fallback, fallback_reason, evidence_hash, content, input_tokens, output_tokens, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""",
                id, n.runId(), n.baselineRunId(), n.type().name(), n.question(), n.provider(), n.model(),
                n.fallback(), n.fallbackReason(), n.evidenceHash(), json(n.content()), n.inputTokens(),
                n.outputTokens(), Timestamp.from(n.createdAt()));
        List<Object[]> rows = new ArrayList<>();
        for (EvidenceItem item : n.evidence()) {
            rows.add(new Object[] {UUID.randomUUID(), id, item.key(), item.label(), item.value(), item.text(),
                    item.unit(), json(item.display()), item.sourceRunId(), item.stageId(), item.resourceId(),
                    Timestamp.from(n.createdAt())});
        }
        jdbc.batchUpdate("""
                insert into insight_evidence (id, insight_id, metric_key, label, value_numeric, value_text, unit,
                    display, source_run_id, stage_id, resource_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""", rows);
        return find(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<InsightResponse> find(UUID id) {
        return attach(jdbc.query("select * from simulation_insight where id = ?", this::insight, id)).stream()
                .findFirst();
    }

    /** The newest stored insight that the given provider wrote for exactly this evidence, if any. */
    @Transactional(readOnly = true)
    public Optional<InsightResponse> findCached(UUID runId, UUID baselineRunId, InsightType type,
            String evidenceHash, String provider) {
        List<InsightResponse> found = jdbc.query("""
                select * from simulation_insight
                where run_id = ? and baseline_run_id is not distinct from ? and insight_type = ?
                  and evidence_hash = ? and provider = ? and not fallback
                order by created_at desc, id desc limit 1""",
                this::insight, runId, baselineRunId, type.name(), evidenceHash, provider);
        return attach(found).stream().findFirst();
    }

    /** Newest first; {@code type} null for every type. */
    @Transactional(readOnly = true)
    public List<InsightResponse> list(UUID runId, InsightType type, int limit, long offset) {
        return attach(jdbc.query("""
                select * from simulation_insight
                where run_id = ? and (?::varchar is null or insight_type = ?::varchar)
                order by created_at desc, id desc limit ? offset ?""",
                this::insight, runId, typeName(type), typeName(type), limit, offset));
    }

    /** Input and output tokens of every language-model call stored since {@code from}: {input, output}. */
    @Transactional(readOnly = true)
    public long[] tokensSince(Instant from) {
        return jdbc.queryForObject("""
                select coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0) from simulation_insight
                where created_at >= ?""", (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)},
                Timestamp.from(from));
    }

    @Transactional(readOnly = true)
    public long count(UUID runId, InsightType type) {
        Long count = jdbc.queryForObject("""
                select count(*) from simulation_insight
                where run_id = ? and (?::varchar is null or insight_type = ?::varchar)""",
                Long.class, runId, typeName(type), typeName(type));
        return count == null ? 0 : count;
    }

    // ---- Mapping -----------------------------------------------------------------------------

    private InsightResponse insight(ResultSet rs, int row) throws SQLException {
        return new InsightResponse(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("baseline_run_id", UUID.class), InsightType.valueOf(rs.getString("insight_type")),
                rs.getString("question"), AiDtos.AI_INTERPRETATION, rs.getString("provider"), rs.getString("model"),
                rs.getBoolean("fallback"), rs.getString("fallback_reason"), (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"), rs.getTimestamp("created_at").toInstant(),
                read(rs.getString("content"), InsightContent.class), List.of());
    }

    private List<InsightResponse> attach(List<InsightResponse> insights) {
        if (insights.isEmpty()) {
            return insights;
        }
        Map<UUID, List<EvidenceItemResponse>> byInsight = new HashMap<>();
        String placeholders = String.join(",", insights.stream().map(i -> "?").toList());
        jdbc.query("select * from insight_evidence where insight_id in (" + placeholders + ") order by created_at, "
                        + "metric_key",
                rs -> {
                    byInsight.computeIfAbsent(rs.getObject("insight_id", UUID.class), k -> new ArrayList<>())
                            .add(new EvidenceItemResponse(rs.getString("metric_key"), rs.getString("label"),
                                    rs.getBigDecimal("value_numeric"), rs.getString("value_text"), rs.getString("unit"),
                                    readStrings(rs.getString("display")), rs.getObject("source_run_id", UUID.class),
                                    rs.getObject("stage_id", UUID.class), rs.getObject("resource_id", UUID.class),
                                    AiDtos.SIMULATED));
                },
                insights.stream().map(InsightResponse::id).toArray());
        return insights.stream()
                .map(i -> new InsightResponse(i.id(), i.runId(), i.baselineRunId(), i.type(), i.question(), i.source(),
                        i.provider(), i.model(), i.fallback(), i.fallbackReason(), i.inputTokens(), i.outputTokens(),
                        i.createdAt(), i.content(), order(i.content(), byInsight.getOrDefault(i.id(), List.of()))))
                .toList();
    }

    /** Evidence in the order the content cites it: top-level references first, then each recommendation's. */
    private static List<EvidenceItemResponse> order(InsightContent content, List<EvidenceItemResponse> items) {
        List<String> keys = citedKeys(content);
        return items.stream()
                .sorted((a, b) -> Integer.compare(index(keys, a.key()), index(keys, b.key())))
                .toList();
    }

    private static int index(List<String> keys, String key) {
        int i = keys.indexOf(key);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Every key the content cites, in order, without repeats. */
    public static List<String> citedKeys(InsightContent content) {
        List<String> keys = new ArrayList<>();
        content.metricRefs().stream().filter(k -> !keys.contains(k)).forEach(keys::add);
        content.recommendations().forEach(r -> r.metricRefs().stream().filter(k -> !keys.contains(k))
                .forEach(keys::add));
        return keys;
    }

    private static String typeName(InsightType type) {
        return type == null ? null : type.name();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Insight could not be written", ex);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored insight could not be read", ex);
        }
    }

    private List<String> readStrings(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, STRINGS);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored evidence could not be read", ex);
        }
    }
}
