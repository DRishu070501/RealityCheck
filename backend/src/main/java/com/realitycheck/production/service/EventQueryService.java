package com.realitycheck.production.service;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.factory.service.FactoryResolver;
import com.realitycheck.factory.service.FactoryService;
import com.realitycheck.production.api.ProductionDtos;
import com.realitycheck.production.api.ProductionDtos.EventResponse;
import com.realitycheck.production.api.ProductionDtos.OriginalValues;
import com.realitycheck.production.api.ProductionDtos.TimelineBucket;
import com.realitycheck.production.api.ProductionDtos.TimelineSummaryResponse;
import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.domain.OperationalEvent;
import com.realitycheck.production.domain.ProductionBatch;
import com.realitycheck.production.domain.ProductionOrder;
import com.realitycheck.production.persistence.OperationalEventRepository;
import com.realitycheck.production.persistence.ProductionBatchRepository;
import com.realitycheck.production.persistence.ProductionOrderRepository;
import com.realitycheck.resource.service.ResourceReader;
import com.realitycheck.resource.service.ResourceReader.ResourceView;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads of the event history: the raw log, the timeline, and per-bucket counts. */
@Service
@Transactional(readOnly = true)
public class EventQueryService {

    /**
     * @param timeline true for the timeline view: corrections are folded into the events they
     *                 change rather than listed, and voided events are left out unless asked for
     */
    public record EventFilter(UUID factoryId, UUID orderId, UUID batchId, UUID stageId, EventType type,
            Instant from, Instant to, boolean timeline, boolean includeVoided) {
    }

    private final OperationalEventRepository events;
    private final ProductionOrderRepository orders;
    private final ProductionBatchRepository batches;
    private final WorkflowReader workflowReader;
    private final ResourceReader resourceReader;
    private final FactoryResolver factoryResolver;
    private final FactoryService factoryService;
    private final NamedParameterJdbcTemplate jdbc;

    public EventQueryService(OperationalEventRepository events, ProductionOrderRepository orders,
            ProductionBatchRepository batches, WorkflowReader workflowReader, ResourceReader resourceReader,
            FactoryResolver factoryResolver, FactoryService factoryService, NamedParameterJdbcTemplate jdbc) {
        this.events = events;
        this.orders = orders;
        this.batches = batches;
        this.workflowReader = workflowReader;
        this.resourceReader = resourceReader;
        this.factoryResolver = factoryResolver;
        this.factoryService = factoryService;
        this.jdbc = jdbc;
    }

    /** Events in the order they happened ({@code ascending}) or newest first. */
    public PageResponse<EventResponse> search(EventFilter filter, boolean ascending, int page, int size) {
        if (filter.from() != null && filter.to() != null && filter.to().isBefore(filter.from())) {
            throw new ValidationException("to", "must not be before from");
        }
        UUID factoryId = factoryResolver.resolve(filter.factoryId());
        Sort sort = ascending
                ? Sort.by(Sort.Order.asc("occurredAt"), Sort.Order.asc("seq"))
                : Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("seq"));
        Page<OperationalEvent> found = events.findAll(specification(factoryId, filter),
                PageRequest.of(page, size, sort));
        List<EventResponse> items = toResponses(factoryId, found.getContent());
        return PageResponse.of(items, found.getNumber(), found.getSize(), found.getTotalElements());
    }

    /** Converts stored events to responses, adding names and what corrections have since changed. */
    public List<EventResponse> toResponses(UUID factoryId, List<OperationalEvent> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        Map<UUID, ProductionOrder> orderById = byId(orders.findAllById(ids(page, OperationalEvent::getOrderId)),
                ProductionOrder::getId);
        Map<UUID, ProductionBatch> batchById = byId(batches.findAllById(ids(page, OperationalEvent::getBatchId)),
                ProductionBatch::getId);
        Map<UUID, StageView> stageById = byId(workflowReader.activeStages(factoryId), StageView::id);
        Map<UUID, ResourceView> resourceById = resourceReader.byIds(ids(page, OperationalEvent::getResourceId));
        Map<UUID, List<OperationalEvent>> corrections = events
                .findByCorrectsEventIdInOrderBySeqAsc(ids(page, OperationalEvent::getId)).stream()
                .collect(Collectors.groupingBy(OperationalEvent::getCorrectsEventId, LinkedHashMap::new,
                        Collectors.toList()));

        List<EventResponse> responses = new ArrayList<>();
        for (OperationalEvent event : page) {
            BigDecimal stones = event.getStoneQuantity();
            BigDecimal carats = event.getCaratQuantity();
            Long duration = event.getDurationSeconds();
            boolean voided = false;
            boolean corrected = false;
            for (OperationalEvent correction : corrections.getOrDefault(event.getId(), List.of())) {
                if (correction.isVoids()) {
                    voided = true;
                    continue;
                }
                corrected = true;
                stones = correction.getStoneQuantity() != null ? correction.getStoneQuantity() : stones;
                carats = correction.getCaratQuantity() != null ? correction.getCaratQuantity() : carats;
                duration = correction.getDurationSeconds() != null ? correction.getDurationSeconds() : duration;
            }
            ProductionOrder order = orderById.get(event.getOrderId());
            ProductionBatch batch = batchById.get(event.getBatchId());
            StageView stage = stageById.get(event.getStageId());
            ResourceView resource = resourceById.get(event.getResourceId());
            responses.add(new EventResponse(event.getId(), event.getSeq(), event.getEventType(),
                    event.getOccurredAt(), event.getCreatedAt(),
                    event.getOrderId(), order == null ? null : order.getOrderNumber(),
                    event.getBatchId(), batch == null ? null : batch.getBatchNumber(),
                    event.getStageId(), stage == null ? null : stage.code(), stage == null ? null : stage.name(),
                    event.getResourceId(), resource == null ? null : resource.name(),
                    stones, carats, duration, event.getPayload(), event.getCorrectsEventId(), event.isVoids(),
                    corrected, voided,
                    corrected ? new OriginalValues(event.getStoneQuantity(), event.getCaratQuantity(),
                            event.getDurationSeconds()) : null,
                    ProductionDtos.ACTUAL));
        }
        return responses;
    }

    /**
     * Counts of timeline events per hour or day of factory-local time, oldest first. Buckets with
     * no events are absent.
     */
    public TimelineSummaryResponse summary(UUID factoryIdOrNull, String bucket) {
        UUID factoryId = factoryResolver.resolve(factoryIdOrNull);
        String unit = switch (bucket == null ? "DAY" : bucket.toUpperCase()) {
            case "DAY" -> "day";
            case "HOUR" -> "hour";
            default -> throw new ValidationException("bucket", "must be DAY or HOUR");
        };
        String zone = factoryService.calendar(factoryId).zone().getId();
        // The unit is one of two literals chosen above, never request text.
        String sql = """
                select date_trunc('%s', e.occurred_at at time zone :zone) at time zone :zone as bucket_start,
                       e.event_type, count(*) as events
                from operational_event e
                where e.factory_id = :factoryId
                  and e.event_type <> 'CORRECTION'
                  and not exists (select 1 from operational_event c
                                  where c.corrects_event_id = e.id and c.voids)
                group by 1, 2
                order by 1, 2""".formatted(unit);
        Map<Instant, Map<EventType, Long>> counts = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource().addValue("zone", zone).addValue("factoryId", factoryId), row -> {
            Instant start = row.getTimestamp("bucket_start").toInstant();
            counts.computeIfAbsent(start, key -> new EnumMap<>(EventType.class))
                    .put(EventType.valueOf(row.getString("event_type")), row.getLong("events"));
        });
        List<TimelineBucket> buckets = counts.entrySet().stream()
                .map(entry -> new TimelineBucket(entry.getKey(),
                        entry.getValue().values().stream().mapToLong(Long::longValue).sum(), entry.getValue()))
                .toList();
        return new TimelineSummaryResponse(unit.toUpperCase(), zone, events.findFirstOccurrence(factoryId),
                events.findLastOccurrence(factoryId), buckets, ProductionDtos.ACTUAL);
    }

    private static Specification<OperationalEvent> specification(UUID factoryId, EventFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("factoryId"), factoryId));
            if (filter.orderId() != null) {
                where.add(cb.equal(root.get("orderId"), filter.orderId()));
            }
            if (filter.batchId() != null) {
                where.add(cb.equal(root.get("batchId"), filter.batchId()));
            }
            if (filter.stageId() != null) {
                where.add(cb.equal(root.get("stageId"), filter.stageId()));
            }
            if (filter.type() != null) {
                where.add(cb.equal(root.get("eventType"), filter.type()));
            }
            if (filter.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
            }
            if (filter.to() != null) {
                where.add(cb.lessThanOrEqualTo(root.get("occurredAt"), filter.to()));
            }
            if (filter.timeline()) {
                where.add(cb.notEqual(root.get("eventType"), EventType.CORRECTION));
                if (!filter.includeVoided()) {
                    Subquery<UUID> voiding = query.subquery(UUID.class);
                    Root<OperationalEvent> correction = voiding.from(OperationalEvent.class);
                    voiding.select(correction.get("id")).where(
                            cb.equal(correction.get("correctsEventId"), root.get("id")),
                            cb.isTrue(correction.get("voids")));
                    where.add(cb.not(cb.exists(voiding)));
                }
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static List<UUID> ids(List<OperationalEvent> page, Function<OperationalEvent, UUID> getter) {
        return page.stream().map(getter).filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<UUID, T> byId(Iterable<T> items, Function<T, UUID> id) {
        Map<UUID, T> map = new HashMap<>();
        for (T item : items) {
            map.put(id.apply(item), item);
        }
        return map;
    }
}
