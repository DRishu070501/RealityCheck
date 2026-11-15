package com.realitycheck.ai.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.realitycheck.ai.domain.InsightContent;
import com.realitycheck.ai.domain.InsightType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shapes of the AI API. Insights carry {@code source: "AI_INTERPRETATION"}; their evidence is SIMULATED. */
public final class AiDtos {

    public static final String AI_INTERPRETATION = "AI_INTERPRETATION";
    public static final String SIMULATED = "SIMULATED";
    public static final int QUESTION_MAX = 500;

    private AiDtos() {
    }

    /**
     * @param baselineRunId required for {@code COMPARISON_SUMMARY}; optional otherwise
     * @param regenerate    ask the provider again even when a stored insight matches
     */
    public record InsightRequest(@NotNull UUID runId, UUID baselineRunId, @NotNull InsightType type,
            Boolean regenerate) {
    }

    public record AskRequest(@NotNull UUID runId, UUID baselineRunId,
            @NotBlank @Size(max = QUESTION_MAX) String question) {
    }

    /**
     * @param provider       who wrote the content: {@code template} (built-in analyst) or a vendor
     * @param model          the model; null for the template analyst
     * @param fallback       the language model failed or its answer did not pass the check, so the
     *                       built-in analyst answered
     * @param fallbackReason {@code VALIDATION_FAILED}, {@code PROVIDER_ERROR} or {@code TIMEOUT}
     * @param inputTokens    tokens sent to the language model, over every attempt; null for the template
     * @param evidence       the evidence items the content cites, as they were when it was written
     */
    public record InsightResponse(UUID id, UUID runId, UUID baselineRunId, InsightType type, String question,
            String source, String provider, String model, boolean fallback, String fallbackReason,
            Integer inputTokens, Integer outputTokens, Instant createdAt, InsightContent content,
            List<EvidenceItemResponse> evidence) {
    }

    /** One cited fact, read from stored simulation results. */
    public record EvidenceItemResponse(String key, String label,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal value, String text, String unit,
            List<String> display, UUID sourceRunId, UUID stageId, UUID resourceId, String source) {
    }
}
