package com.realitycheck.ai.provider;

/**
 * @param rawJson      the structured output, not yet validated
 * @param model        the model that answered; null for the template analyst
 * @param inputTokens  null when the provider does not report usage
 * @param truncated    the provider stopped at the output-token limit, so the JSON is incomplete
 */
public record AiResponse(String rawJson, String model, Integer inputTokens, Integer outputTokens,
        boolean truncated) {

    public AiResponse(String rawJson, String model, Integer inputTokens, Integer outputTokens) {
        this(rawJson, model, inputTokens, outputTokens, false);
    }
}
