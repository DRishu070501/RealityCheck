package com.realitycheck.production.api;

import com.realitycheck.production.api.ProductionDtos.FactoryOverviewResponse;
import com.realitycheck.production.api.ProductionDtos.FactoryStateResponse;
import com.realitycheck.production.api.ProductionDtos.InsightsResponse;
import com.realitycheck.production.service.FactoryStateService;
import com.realitycheck.production.service.InsightsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lives with the production module because the data does; the paths sit under
 * {@code /api/factories} because that is where a client looks for them.
 */
@RestController
@RequestMapping(path = "/api/factories/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Production")
public class FactoryStateController {

    private final FactoryStateService factoryStateService;
    private final InsightsService insightsService;

    public FactoryStateController(FactoryStateService factoryStateService, InsightsService insightsService) {
        this.factoryStateService = factoryStateService;
        this.insightsService = insightsService;
    }

    @GetMapping("/insights")
    @Operation(summary = "Where time goes: waiting and working or held time per stage, from recorded history")
    public InsightsResponse insights(@PathVariable UUID id, @RequestParam(required = false) Instant until) {
        return insightsService.insights(id, until);
    }

    @GetMapping("/state")
    @Operation(summary = "The factory as it stood at an instant, rebuilt from the event history")
    public FactoryStateResponse state(@PathVariable UUID id, @RequestParam(required = false) Instant at) {
        return factoryStateService.stateAt(id, at);
    }

    @GetMapping("/overview")
    @Operation(summary = "Order counts, stage queues, units in use, daily output and recent events, as of now")
    public FactoryOverviewResponse overview(@PathVariable UUID id) {
        return factoryStateService.overview(id);
    }
}
