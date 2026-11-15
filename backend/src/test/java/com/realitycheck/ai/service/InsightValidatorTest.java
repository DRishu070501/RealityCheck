package com.realitycheck.ai.service;

import static com.realitycheck.ai.EvidenceFixtures.baseline;
import static com.realitycheck.ai.EvidenceFixtures.comparison;
import static com.realitycheck.ai.EvidenceFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.ai.domain.InsightContent;
import com.realitycheck.ai.domain.InsightContent.Recommendation;
import com.realitycheck.ai.evidence.Evidence;
import com.realitycheck.ai.evidence.EvidenceBuilder;
import com.realitycheck.ai.service.InsightValidator.Result;
import com.realitycheck.ai.service.InsightValidator.ValidationError;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsightValidatorTest {

    private final Evidence evidence = EvidenceBuilder.build(run(), baseline(), comparison(run(), baseline()));

    private static InsightContent content(String summary, List<Recommendation> recs, List<String> refs) {
        return new InsightContent(summary, List.of(), List.of(), "", recs, refs, List.of(), List.of());
    }

    private static InsightContent summary(String text) {
        return content(text, List.of(), List.of("run.summary.elapsedSeconds"));
    }

    private Result check(InsightContent content) {
        return InsightValidator.validate(content, evidence);
    }

    private static List<String> codes(Result result) {
        return result.errors().stream().map(ValidationError::code).toList();
    }

    @Test
    void numbersFromValuesAndDisplayStringsPass() {
        Result result = check(summary("The work takes 52.0 hours (2.2 days, about 52 hours); Wheels are busy 96.2%,"
                + " roughly 96%, and total cost is INR 1,234,567.89. Completion improves by 12.0 hours (-18.8%)."));

        assertThat(result.errors()).isEmpty();
        assertThat(result.content()).isNotNull();
    }

    @Test
    void quotedEvidenceTextWithDigitsPasses() {
        Result result = check(summary("Order SDW-2026-0001 is LATE. The change “Wheels capacity 2 → 4” was simulated"
                + " from 2026-11-03 00:30 UTC with algorithm 1.0.0."));

        assertThat(result.errors()).isEmpty();
    }

    @Test
    void aFabricatedNumberIsRejected() {
        Result result = check(summary("The work takes 47 hours."));

        assertThat(codes(result)).containsExactly(ValidationError.UNSUPPORTED_NUMBER);
        assertThat(result.errors().get(0).token()).isEqualTo("47");
        assertThat(result.errors().get(0).field()).isEqualTo("summary");
        assertThat(result.content()).isNull();
    }

    @Test
    void numbersInEveryTextFieldAreChecked() {
        InsightContent c = new InsightContent("Fine.", List.of("Up 13.7%."), List.of("Risk 999."), "Wait 1.23 days.",
                List.of(new Recommendation("Add 777 machines", "Because 0.5.", List.of("run.cost.total"))),
                List.of(), List.of("Assume 42."), List.of("Limit 8.8."));

        assertThat(check(c).errors()).extracting(ValidationError::field)
                .contains("keyChanges[0]", "risks[0]", "bottleneckExplanation", "recommendations[0].title",
                        "assumptions[0]", "limitations[0]");
    }

    @Test
    void aSavingMustBeACostDifference() {
        // The cost difference is +INR 34,567.89.
        assertThat(check(summary("This would save INR 34,567.89 compared with the baseline.")).errors()).isEmpty();
        Result invented = check(summary("Total cost is INR 1,234,567.89. You save INR 1,234,567.89 overall."));

        assertThat(codes(invented)).containsExactly(ValidationError.UNSUPPORTED_SAVING_CLAIM);
    }

    @Test
    void unknownReferencesAndRecommendationsWithoutReferencesAreRejected() {
        Result result = check(content("Fine.",
                List.of(new Recommendation("Act", "Because.", List.of()),
                        new Recommendation("Act", "Because.", List.of("run.summary.madeUp"))),
                List.of("run.nothing")));

        assertThat(codes(result)).containsExactlyInAnyOrder(ValidationError.UNKNOWN_METRIC_REF,
                ValidationError.MISSING_METRIC_REF, ValidationError.UNKNOWN_METRIC_REF);
    }

    @Test
    void aReferenceToAnItemsFieldMeansTheItem() {
        Result result = check(content("Fine.", List.of(), List.of("run.cost.total.display", "run.cost.total")));

        assertThat(result.errors()).isEmpty();
        assertThat(result.content().metricRefs()).containsExactly("run.cost.total");
    }

    @Test
    void malformedAndIncompleteJsonAreRejected() {
        assertThat(codes(InsightValidator.validate("{\"summary\": ", evidence)))
                .containsExactly(ValidationError.MALFORMED_JSON);
        assertThat(codes(InsightValidator.validate("", evidence))).containsExactly(ValidationError.MALFORMED_JSON);
        assertThat(codes(InsightValidator.validate("{\"summary\": \"Hi\"}", evidence)))
                .containsExactly(ValidationError.SCHEMA);
        String extra = """
                {"summary":"Hi","keyChanges":[],"risks":[],"bottleneckExplanation":"","recommendations":[],
                 "metricRefs":[],"assumptions":[],"limitations":[],"confidence":"high"}""";
        assertThat(codes(InsightValidator.validate(extra, evidence))).containsExactly(ValidationError.SCHEMA);
    }

    @Test
    void validJsonPassesEvenInsideACodeFence() {
        String json = """
                ```json
                {"summary":"It takes 52.0 hours.","keyChanges":[],"risks":[],"bottleneckExplanation":"",
                 "recommendations":[{"title":"Look","rationale":"Busy 96.2%.","metricRefs":["run.cost.total"]}],
                 "metricRefs":["run.summary.elapsedSeconds"],"assumptions":[],"limitations":[]}
                ```""";

        assertThat(InsightValidator.validate(json, evidence).errors()).isEmpty();
    }

    @Test
    void lengthAndListLimitsAreEnforced() {
        assertThat(codes(check(summary("x".repeat(1201))))).containsExactly(ValidationError.SCHEMA);
        InsightContent nine = new InsightContent("Fine.", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"),
                List.of(), "", List.of(), List.of(), List.of(), List.of());
        assertThat(codes(check(nine))).containsExactly(ValidationError.SCHEMA);
    }
}
