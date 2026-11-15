package com.realitycheck.ai.domain;

import java.util.List;

/**
 * The structured output every provider must return, validated before anyone sees it. Text may
 * state only numbers present in the evidence; {@code metricRefs} name evidence keys.
 */
public record InsightContent(
        String summary,
        List<String> keyChanges,
        List<String> risks,
        String bottleneckExplanation,
        List<Recommendation> recommendations,
        List<String> metricRefs,
        List<String> assumptions,
        List<String> limitations) {

    /** @param metricRefs at least one evidence key supporting the recommendation */
    public record Recommendation(String title, String rationale, List<String> metricRefs) {
    }
}
