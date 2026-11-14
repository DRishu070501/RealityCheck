package com.realitycheck.factory.api;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.api.Paging;
import com.realitycheck.factory.api.FactoryDtos.CreateFactoryRequest;
import com.realitycheck.factory.api.FactoryDtos.FactoryResponse;
import com.realitycheck.factory.api.FactoryDtos.FactorySummaryResponse;
import com.realitycheck.factory.service.FactoryService;
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
@RequestMapping(path = "/api/factories", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Factory")
public class FactoryController {

    private final FactoryService factoryService;

    public FactoryController(FactoryService factoryService) {
        this.factoryService = factoryService;
    }

    @GetMapping
    @Operation(summary = "List factories, ordered by name")
    public PageResponse<FactorySummaryResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return factoryService.list(Paging.of(page, size, Sort.by("name", "id")));
    }

    @GetMapping("/{id}")
    @Operation(summary = "A factory with its working calendar and configuration counts")
    public FactoryResponse get(@PathVariable UUID id) {
        return factoryService.get(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a factory with its weekly working calendar")
    public ResponseEntity<FactoryResponse> create(@Valid @RequestBody CreateFactoryRequest request) {
        FactoryResponse created = factoryService.create(request);
        return ResponseEntity.created(URI.create("/api/factories/" + created.id())).body(created);
    }
}
