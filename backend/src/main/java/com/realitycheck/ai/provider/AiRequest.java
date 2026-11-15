package com.realitycheck.ai.provider;

import com.realitycheck.ai.domain.InsightType;
import com.realitycheck.ai.evidence.Evidence;

/**
 * @param systemPrompt rules and the output schema; never contains user text
 * @param userMessage  the evidence block, the question block for answers and, on a retry, the
 *                     reasons the previous response was rejected
 * @param evidence     the same evidence as structured data, for providers that do not read prose
 * @param question     the user's question for {@code ANSWER}; null otherwise
 */
public record AiRequest(InsightType type, String systemPrompt, String userMessage, Evidence evidence,
        String question, int maxOutputTokens) {
}
