package com.realitycheck.ai.provider;

import static com.realitycheck.ai.EvidenceFixtures.baseline;
import static com.realitycheck.ai.EvidenceFixtures.comparison;
import static com.realitycheck.ai.EvidenceFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.ai.EvidenceFixtures;
import com.realitycheck.ai.domain.InsightContent;
import com.realitycheck.ai.domain.InsightType;
import com.realitycheck.ai.evidence.Evidence;
import com.realitycheck.ai.evidence.EvidenceBuilder;
import com.realitycheck.ai.evidence.RunInput;
import com.realitycheck.ai.service.InsightValidator;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TemplateAiProviderTest {

    private final TemplateAiProvider template = new TemplateAiProvider();
    private final Evidence single = EvidenceBuilder.build(run(), null, null);
    private final Evidence compared = EvidenceBuilder.build(run(), baseline(), comparison(run(), baseline()));

    @ParameterizedTest
    @EnumSource(InsightType.class)
    void everyTypePassesTheValidatorWithAndWithoutABaseline(InsightType type) {
        for (Evidence evidence : new Evidence[] {single, compared}) {
            InsightContent content = template.content(type, evidence, "Where is the bottleneck?");
            assertThat(InsightValidator.validate(content, evidence).errors()).as(type + " " + content).isEmpty();
            assertThat(content.summary()).isNotBlank();
            assertThat(content.recommendations()).allSatisfy(r -> assertThat(r.metricRefs()).isNotEmpty());
        }
    }

    @Test
    void bottleneckNamesTheStageItsPoolAndCitesThem() {
        InsightContent content = template.content(InsightType.BOTTLENECK, single, null);

        assertThat(content.bottleneckExplanation()).contains("Polishing", "Wheels", "96.2%");
        assertThat(content.recommendations().get(0).title()).isEqualTo("Simulate more capacity for Wheels");
        assertThat(content.recommendations().get(0).metricRefs())
                .containsExactly("run.bottleneck.primaryStage", "run.stage.POLISHING.limitingUtilization");
        // The spare pool is busy 31%: below the under-use threshold.
        assertThat(content.recommendations()).anySatisfy(r -> assertThat(r.title()).contains("Spare"));
    }

    @Test
    void comparisonStatesTheDifferences() {
        InsightContent content = template.content(InsightType.COMPARISON_SUMMARY, compared, null);

        assertThat(content.summary()).contains("Current factory operations", "completion time by -12.0 hours",
                "(-18.8%)");
        assertThat(content.keyChanges()).anySatisfy(c -> assertThat(c).startsWith("Completion time: 64.0 hours to"
                + " 52.0 hours"));
        assertThat(content.metricRefs()).contains("delta.summary.elapsedSeconds.absolute");
    }

    @Test
    void delayAnalysisListsTheLateOrders() {
        InsightContent content = template.content(InsightType.DELAY_ANALYSIS, single, null);

        assertThat(content.summary()).isEqualTo("1 of 10 orders are delayed in this simulation; the on-time"
                + " delivery rate is 90.0%.");
        assertThat(content.risks()).singleElement().asString().startsWith("SDW-2026-0001: LATE, due");
    }

    @Test
    void questionsAreAnsweredFromAFixedSetOfIntents() {
        assertThat(answer("Which resource is idle the most?")).contains("Spare", "31.0%");
        assertThat(answer("Where do jobs wait longest?")).contains("Work waits longest at Polishing");
        assertThat(answer("How much does it cost?")).contains("INR 1,234,567.89");
        assertThat(answer("What if we hired more polishers?")).contains("has not been simulated");
        assertThat(answer("Compare with the baseline")).contains("No baseline run was chosen");
        assertThat(answer("What colour are the walls?")).contains("outside those topics");
    }

    @Property(tries = 200)
    void outputAlwaysValidatesForRandomResults(@ForAll long seed, @ForAll boolean withBaseline) {
        RunInput run = EvidenceFixtures.random(seed, UUID.randomUUID(), "Scenario " + (seed & 7));
        RunInput base = EvidenceFixtures.random(seed + 1, UUID.randomUUID(), "Baseline");
        Evidence evidence = withBaseline ? EvidenceBuilder.build(run, base, comparison(run, base))
                : EvidenceBuilder.build(run, null, null);
        for (InsightType type : InsightType.values()) {
            InsightContent content = template.content(type, evidence, "Why are orders late and what does it cost?");
            assertThat(InsightValidator.validate(content, evidence).errors()).as(type.name()).isEmpty();
        }
    }

    private String answer(String question) {
        return template.content(InsightType.ANSWER, single, question).summary();
    }
}
