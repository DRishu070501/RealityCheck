package com.realitycheck.production.api;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.api.Paging;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.production.api.ProductionDtos.AppendEventRequest;
import com.realitycheck.production.api.ProductionDtos.EventResponse;
import com.realitycheck.production.api.ProductionDtos.TimelineSummaryResponse;
import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.domain.OperationalEvent;
import com.realitycheck.production.service.EventAppendService;
import com.realitycheck.production.service.EventQueryService;
import com.realitycheck.production.service.EventQueryService.EventFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Production")
public class EventController {

    private final EventAppendService eventAppendService;
    private final EventQueryService eventQueryService;

    public EventController(EventAppendService eventAppendService, EventQueryService eventQueryService) {
        this.eventAppendService = eventAppendService;
        this.eventQueryService = eventQueryService;
    }

    @GetMapping("/events")
    @Operation(summary = "The raw event log, corrections included, in the order things happened")
    public PageResponse<EventResponse> events(
            @RequestParam(required = false) UUID factoryId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) UUID batchId,
            @RequestParam(required = false) UUID stageId,
            @RequestParam(required = false) EventType type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "asc") String order,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return search(new EventFilter(factoryId, orderId, batchId, stageId, type, from, to, false, true), order,
                page, size);
    }

    @GetMapping("/timeline")
    @Operation(summary = "What happened, as now known: corrections applied, voided events left out")
    public PageResponse<EventResponse> timeline(
            @RequestParam(required = false) UUID factoryId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) UUID batchId,
            @RequestParam(required = false) UUID stageId,
            @RequestParam(required = false) EventType type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "false") boolean includeVoided,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return search(new EventFilter(factoryId, orderId, batchId, stageId, type, from, to, true, includeVoided),
                order, page, size);
    }

    @GetMapping("/timeline/summary")
    @Operation(summary = "Event counts per day or hour of factory-local time")
    public TimelineSummaryResponse summary(
            @RequestParam(required = false) UUID factoryId,
            @RequestParam(defaultValue = "DAY") String bucket) {
        return eventQueryService.summary(factoryId, bucket);
    }

    @PostMapping(path = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Record a stage start, a stage completion, a dispatch, or a correction")
    public ResponseEntity<EventResponse> append(@Valid @RequestBody AppendEventRequest request) {
        OperationalEvent event = eventAppendService.append(request);
        EventResponse response = eventQueryService.toResponses(event.getFactoryId(), List.of(event)).get(0);
        return ResponseEntity.status(201).body(response);
    }

    private PageResponse<EventResponse> search(EventFilter filter, String order, int page, Integer size) {
        boolean ascending = switch (order.toLowerCase()) {
            case "asc" -> true;
            case "desc" -> false;
            default -> throw new ValidationException("order", "must be asc or desc");
        };
        int pageSize = Paging.of(page, size, Sort.unsorted()).getPageSize();
        return eventQueryService.search(filter, ascending, page, pageSize);
    }
}
