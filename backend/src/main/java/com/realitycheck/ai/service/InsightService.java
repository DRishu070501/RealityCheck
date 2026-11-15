package com.realitycheck.ai.service;

import com.realitycheck.ai.api.AiDtos.InsightResponse;
import com.realitycheck.ai.domain.InsightContent;
import com.realitycheck.ai.domain.InsightType;
import com.realitycheck.ai.evidence.Evidence;
import com.realitycheck.ai.evidence.Evidence.EvidenceItem;
import com.realitycheck.ai.evidence.EvidenceJson;
import com.realitycheck.ai.provider.AiProvider;
import com.realitycheck.ai.provider.AiProviderConfig.AiProviders;
import com.realitycheck.ai.provider.AiProviderException;
import com.realitycheck.ai.provider.AiRequest;
import com.realitycheck.ai.provider.AiResponse;
import com.realitycheck.ai.service.InsightStore.NewInsight;
import com.realitycheck.ai.service.InsightValidator.ValidationError;
import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.config.RealityCheckProperties;
import com.realitycheck.common.error.ApiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Produces insights: evidence from stored results, a provider call with one corrective retry,
 * validation, the template analyst as fallback, and storage. The language-model call happens
 * outside any database transaction. Nothing here writes simulation data.
 */
@Service
public class InsightService {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    /** Retry note only (not a fallback reason): the model hit the output-token limit. */
    static final String TRUNCATED = "TRUNCATED";

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);
    private static final int ATTEMPTS = 2;

    private final EvidenceSource evidenceSource;
    private final AiProviders providers;
    private final InsightStore store;
    private final AiRateLimiter rateLimiter;
    private final Clock clock;
    private final int maxOutputTokens;

    public InsightService(EvidenceSource evidenceSource, AiProviders providers, InsightStore store,
            AiRateLimiter rateLimiter, Clock clock, RealityCheckProperties properties) {
        this.evidenceSource = evidenceSource;
        this.providers = providers;
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.maxOutputTokens = properties.ai().maxOutputTokens();
    }

    public InsightResponse insight(UUID runId, UUID baselineRunId, InsightType type, boolean regenerate) {
        if (type == InsightType.ANSWER) {
            throw ApiException.badRequest("Use POST /api/ai/ask to ask a question.");
        }
        if (type.requiresBaseline() && baselineRunId == null) {
            throw ApiException.badRequest(type + " needs a baselineRunId.");
        }
        return generate(runId, baselineRunId, type, null, regenerate);
    }

    public InsightResponse ask(UUID runId, UUID baselineRunId, String question) {
        return generate(runId, baselineRunId, InsightType.ANSWER, question.strip(), true);
    }

    public PageResponse<InsightResponse> history(UUID runId, InsightType type, int page, int size) {
        evidenceSource.requireRun(runId);
        return PageResponse.of(store.list(runId, type, size, (long) page * size), page, size,
                store.count(runId, type));
    }

    private InsightResponse generate(UUID runId, UUID baselineRunId, InsightType type, String question,
            boolean regenerate) {
        rateLimiter.acquire();
        Evidence evidence = evidenceSource.load(runId, baselineRunId);
        String hash = EvidenceJson.hash(evidence);
        AiProvider active = providers.active();
        if (!regenerate) {
            Optional<InsightResponse> stored = store.findCached(runId, baselineRunId, type, hash, active.name());
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        Outcome outcome = produce(active, type, evidence, question);
        List<String> cited = InsightStore.citedKeys(outcome.content());
        List<EvidenceItem> citedItems = cited.stream().map(evidence::item).flatMap(Optional::stream).toList();
        return store.save(new NewInsight(runId, baselineRunId, type, question, outcome.provider(), outcome.model(),
                outcome.fallbackReason() != null, outcome.fallbackReason(), hash, outcome.content(),
                outcome.inputTokens(), outcome.outputTokens(), citedItems, clock.instant()));
    }

    private record Outcome(InsightContent content, String provider, String model, String fallbackReason,
            Integer inputTokens, Integer outputTokens) {
    }

    private Outcome produce(AiProvider active, InsightType type, Evidence evidence, String question) {
        if (!active.languageModel()) {
            return new Outcome(template(type, evidence, question), providers.template().name(), null, null, null, null);
        }
        String system = PromptFactory.systemPrompt(type);
        List<ValidationError> errors = List.of();
        int inputTokens = 0;
        int outputTokens = 0;
        boolean usage = false;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            AiResponse response;
            try {
                response = active.complete(new AiRequest(type, system,
                        PromptFactory.userMessage(evidence, question, errors), evidence, question, maxOutputTokens));
            } catch (AiProviderException ex) {
                log.warn("AI provider {} failed for run {} ({}): {}", active.name(), evidence.run().runId(), type,
                        ex.getMessage());
                return fallback(type, evidence, question, ex.reason().name(), usage ? inputTokens : null,
                        usage ? outputTokens : null);
            }
            if (response.inputTokens() != null) {
                usage = true;
                inputTokens += response.inputTokens();
                outputTokens += response.outputTokens() == null ? 0 : response.outputTokens();
            }
            InsightValidator.Result result = InsightValidator.validate(response.rawJson(), evidence);
            if (result.valid()) {
                return new Outcome(result.content(), active.name(), response.model(), null,
                        usage ? inputTokens : null, usage ? outputTokens : null);
            }
            errors = response.truncated()
                    ? List.of(new ValidationError(TRUNCATED, null, null, "Your response was cut off at the length"
                            + " limit, so it was incomplete JSON. Write far less: a summary of two or three sentences,"
                            + " at most four short items per list and at most three recommendations."))
                    : result.errors();
            log.info("AI answer for run {} ({}) failed the evidence check on attempt {}: {}", evidence.run().runId(),
                    type, attempt, errors.stream().map(e -> e.code() + (e.field() == null ? "" : " " + e.field())
                            + (e.token() == null ? "" : " " + e.token())
                            + (ValidationError.SCHEMA.equals(e.code()) ? " (" + e.message() + ")" : ""))
                            .distinct().limit(10).toList());
        }
        return fallback(type, evidence, question, VALIDATION_FAILED, usage ? inputTokens : null,
                usage ? outputTokens : null);
    }

    private Outcome fallback(InsightType type, Evidence evidence, String question, String reason,
            Integer inputTokens, Integer outputTokens) {
        InsightContent content = template(type, evidence, question);
        List<String> limitations = new ArrayList<>();
        limitations.add(VALIDATION_FAILED.equals(reason)
                ? "The language model's answer did not pass the evidence check, so the built-in analyst answered."
                : "The language model was unavailable, so the built-in analyst answered.");
        limitations.addAll(content.limitations());
        content = new InsightContent(content.summary(), content.keyChanges(), content.risks(),
                content.bottleneckExplanation(), content.recommendations(), content.metricRefs(),
                content.assumptions(), limitations.stream().limit(InsightValidator.LIST_MAX).toList());
        return new Outcome(content, providers.template().name(), null, reason, inputTokens, outputTokens);
    }

    private InsightContent template(InsightType type, Evidence evidence, String question) {
        InsightContent content = providers.template().content(type, evidence, question);
        InsightValidator.Result check = InsightValidator.validate(content, evidence);
        if (!check.valid()) {
            // A defect in the template analyst, never in the data: report it, still answer.
            log.error("Template insight for run {} ({}) failed validation: {}", evidence.run().runId(), type,
                    check.errors());
        }
        return content;
    }
}
