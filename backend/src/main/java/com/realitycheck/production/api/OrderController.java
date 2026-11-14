package com.realitycheck.production.api;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.api.Paging;
import com.realitycheck.production.api.ProductionDtos.CreateOrderRequest;
import com.realitycheck.production.api.ProductionDtos.OrderDetailResponse;
import com.realitycheck.production.api.ProductionDtos.OrderResponse;
import com.realitycheck.production.domain.OrderStatus;
import com.realitycheck.production.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Production")
public class OrderController {

    /**
     * Earliest due first. Orders without a due date come last because PostgreSQL sorts nulls
     * last in ascending order; Spring Data cannot express null precedence in criteria queries.
     */
    private static final Sort BY_DUE_DATE = Sort.by(Sort.Order.asc("dueAt"),
            Sort.Order.asc("orderNumber"), Sort.Order.asc("id"));

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @Operation(summary = "List a factory's orders, earliest due first, optionally by status or lateness")
    public PageResponse<OrderResponse> list(
            @RequestParam(required = false) UUID factoryId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Boolean delayed,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return orderService.list(factoryId, status, delayed, Paging.of(page, size, BY_DUE_DATE));
    }

    @GetMapping("/{id}")
    @Operation(summary = "An order with its batches and its full event history")
    public OrderDetailResponse get(@PathVariable UUID id) {
        return orderService.get(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Place an order; its batches are queued at the first stage")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.create(request);
        return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
    }
}
