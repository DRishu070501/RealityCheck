package com.realitycheck.ai.domain;

/** What an insight explains. {@code ANSWER} is a reply to a user's question. */
public enum InsightType {
    EXPLANATION,
    BOTTLENECK,
    RECOMMENDATIONS,
    DELAY_ANALYSIS,
    COMPARISON_SUMMARY,
    EXECUTIVE_REPORT,
    ANSWER;

    public boolean requiresBaseline() {
        return this == COMPARISON_SUMMARY;
    }
}
