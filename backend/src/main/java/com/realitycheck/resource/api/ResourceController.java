package com.realitycheck.resource.api;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.api.Paging;
import com.realitycheck.resource.api.ResourceDtos.AvailabilityResponse;
import com.realitycheck.resource.api.ResourceDtos.CapacityTimelineResponse;
import com.realitycheck.resource.api.ResourceDtos.CreateAvailabilityRequest;
import com.realitycheck.resource.api.ResourceDtos.CreateResourceRequest;
import com.realitycheck.resource.api.ResourceDtos.ResourceResponse;
import com.realitycheck.resource.api.ResourceDtos.UpdateResourceRequest;
import com.realitycheck.resource.domain.ResourceKind;
import com.realitycheck.resource.service.ResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/resources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Resources")
public class ResourceController {

    /** "MACHINE" sorts after "EMPLOYEE" as text, so kind is descending to put machines first. */
    private static final Sort MACHINES_FIRST = Sort.by(Sort.Order.desc("kind"), Sort.Order.asc("name"),
            Sort.Order.asc("id"));

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping
    @Operation(summary = "List a factory's resource pools: machines first, then employees, each by name")
    public PageResponse<ResourceResponse> list(
            @RequestParam(required = false) UUID factoryId,
            @RequestParam(required = false) ResourceKind kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return resourceService.list(factoryId, kind, Paging.of(page, size, MACHINES_FIRST));
    }

    @GetMapping("/{id}")
    @Operation(summary = "A resource pool and the stages that require it")
    public ResourceResponse get(@PathVariable UUID id) {
        return resourceService.get(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a resource pool of machines or employees")
    public ResponseEntity<ResourceResponse> create(@Valid @RequestBody CreateResourceRequest request) {
        ResourceResponse created = resourceService.create(request);
        return ResponseEntity.created(URI.create("/api/resources/" + created.id())).body(created);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a pool; answers 409 if the version is stale")
    public ResourceResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateResourceRequest request) {
        return resourceService.update(id, request);
    }

    @GetMapping("/{id}/availability")
    @Operation(summary = "A pool's dated capacity windows and its effective capacity over a time range")
    public CapacityTimelineResponse availability(
            @PathVariable UUID id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return resourceService.availability(id, from, to);
    }

    @PostMapping(path = "/{id}/availability", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Record a dated capacity change, such as downtime or absence")
    public ResponseEntity<AvailabilityResponse> addAvailability(
            @PathVariable UUID id, @Valid @RequestBody CreateAvailabilityRequest request) {
        AvailabilityResponse created = resourceService.addAvailability(id, request);
        return ResponseEntity.status(201).body(created);
    }
}
