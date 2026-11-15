package com.realitycheck.ai.api;

import com.realitycheck.ai.api.AiDtos.AskRequest;
import com.realitycheck.ai.api.AiDtos.InsightRequest;
import com.realitycheck.ai.api.AiDtos.InsightResponse;
import com.realitycheck.ai.domain.InsightType;
import com.realitycheck.ai.service.InsightService;
import com.realitycheck.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI analyst", description = "Explanations of stored simulation results. AI interprets; it never calculates.")
public class AiController {

    private final InsightService insights;

    public AiController(InsightService insights) {
        this.insights = insights;
    }

    @PostMapping("/insights")
    @Operation(summary = "Explain a completed run",
            description = "Builds evidence from the run's stored results (and the comparison with a baseline), asks"
                    + " the configured provider, checks every number against the evidence and stores the insight."
                    + " Falls back to the built-in analyst with fallback=true when the provider fails.")
    public InsightResponse insight(@Valid @RequestBody InsightRequest request) {
        return insights.insight(request.runId(), request.baselineRunId(), request.type(),
                Boolean.TRUE.equals(request.regenerate()));
    }

    @PostMapping("/ask")
    @Operation(summary = "Ask a question about a completed run",
            description = "Answers only from the run's evidence, in the same checked structure.")
    public InsightResponse ask(@Valid @RequestBody AskRequest request) {
        return insights.ask(request.runId(), request.baselineRunId(), request.question());
    }

    @GetMapping("/insights/{runId}")
    @Operation(summary = "Stored insights of a run, newest first")
    public PageResponse<InsightResponse> history(@PathVariable UUID runId,
            @RequestParam(required = false) InsightType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return insights.history(runId, type, Math.max(0, page), PageResponse.clampSize(size));
    }
}
