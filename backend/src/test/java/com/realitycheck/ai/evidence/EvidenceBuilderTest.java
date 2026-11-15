package com.realitycheck.ai.evidence;

import static com.realitycheck.ai.EvidenceFixtures.baseline;
import static com.realitycheck.ai.EvidenceFixtures.comparison;
import static com.realitycheck.ai.EvidenceFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.ai.EvidenceFixtures;
import com.realitycheck.ai.evidence.Evidence.EvidenceItem;
import com.realitycheck.simulation.api.SimulationDtos.OrderResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceBuilderTest {

    @Test
    void numericFactsHaveKeysUnitsAndDisplayStrings() {
        Evidence evidence = EvidenceBuilder.build(run(), null, null);

        EvidenceItem elapsed = evidence.item("run.summary.elapsedSeconds").orElseThrow();
        assertThat(elapsed.value()).isEqualByComparingTo("187200");
        assertThat(elapsed.unit()).isEqualTo("seconds");
        assertThat(elapsed.display()).containsExactly("52.0 hours", "2.2 days", "52 hours");

        assertThat(evidence.item("run.stage.POLISHING.limitingUtilization").orElseThrow().display())
                .containsExactly("96.2%", "96%");
        assertThat(evidence.item("run.cost.total").orElseThrow().display())
                .containsExactly("INR 1,234,567.89", "INR 1,234,568");
        assertThat(evidence.item("run.resource." + EvidenceFixtures.SPARE + ".utilization").orElseThrow()
                .stageId()).isNull();
        assertThat(evidence.item("run.stage.POLISHING.meanWaitWorkingSeconds").orElseThrow().stageId())
                .isEqualTo(EvidenceFixtures.POLISHING);
    }

    @Test
    void textFactsCarryNamesOutcomesAndChanges() {
        Evidence evidence = EvidenceBuilder.build(run(), null, null);

        assertThat(evidence.item("run.bottleneck.primaryStage").orElseThrow().text()).isEqualTo("Polishing");
        assertThat(evidence.item("run.order.SDW-2026-0001.outcome").orElseThrow().text()).isEqualTo("LATE");
        assertThat(evidence.item("run.order.SDW-2026-0001.number").orElseThrow().text()).isEqualTo("SDW-2026-0001");
        assertThat(evidence.item("run.change.1").orElseThrow().text()).isEqualTo("Wheels capacity 2 → 4");
        // On-time orders keep only their outcome.
        assertThat(evidence.has("run.order.SDW-2026-0005.outcome")).isTrue();
        assertThat(evidence.has("run.order.SDW-2026-0005.dueAt")).isFalse();
        assertThat(evidence.baseline()).isNull();
        assertThat(evidence.withPrefix("delta.")).isEmpty();
    }

    @Test
    void aBaselineAddsItsFactsAndTheComparisonsDifferences() {
        Evidence evidence = EvidenceBuilder.build(run(), baseline(), comparison(run(), baseline()));

        assertThat(evidence.baseline().scenarioName()).isEqualTo("Current factory operations");
        assertThat(evidence.item("baseline.summary.elapsedSeconds").orElseThrow().value()).isEqualByComparingTo("230400");
        EvidenceItem abs = evidence.item("delta.summary.elapsedSeconds.absolute").orElseThrow();
        assertThat(abs.value()).isEqualByComparingTo("-43200");
        assertThat(abs.display()).contains("-12.0 hours");
        assertThat(evidence.item("delta.summary.elapsedSeconds.percent").orElseThrow().display())
                .contains("-18.8%");
        assertThat(evidence.item("delta.summary.elapsedSeconds.direction").orElseThrow().text()).isEqualTo("IMPROVED");
        assertThat(evidence.item("delta.summary.cost.total.absolute").orElseThrow().display())
                .contains("+INR 34,567.89");
        assertThat(evidence.item("delta.summary.machineUtilization.absolute").orElseThrow().unit())
                .isEqualTo("fraction");
        assertThat(evidence.has("delta.summary.machineUtilization.direction")).isFalse();
        assertThat(evidence.item("delta.bottleneck.change").orElseThrow().text()).isEqualTo("SAME");
        assertThat(evidence.item("delta.order.SDW-2026-0002.outcomeChange").orElseThrow().text())
                .isEqualTo("LATE to ON_TIME");
        // Baseline orders and pools are covered by the deltas, not repeated.
        assertThat(evidence.withPrefix("baseline.order.")).isEmpty();
        assertThat(evidence.withPrefix("baseline.resource.")).isEmpty();
    }

    @Test
    void ordersBeyondTheLimitAreDroppedMostDelayedKept() {
        var base = run();
        List<OrderResult> orders = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Instant due = Instant.parse("2026-11-10T00:00:00Z").plusSeconds(i);
            orders.add(new OrderResult(UUID.randomUUID(), "O-%03d".formatted(i), due, due,
                    i == 59 ? "UNFINISHED" : "ON_TIME", i == 59));
        }
        var many = new RunInput(base.runId(), base.scenarioName(), base.algorithmVersion(), base.currencyCode(),
                base.forkAt(), base.terminationReason(), base.summary(), base.bottleneck(), base.stages(),
                base.pools(), orders, base.appliedChanges());

        Evidence evidence = EvidenceBuilder.build(many, null, null);

        assertThat(evidence.truncated()).isTrue();
        assertThat(evidence.items().stream().filter(i -> i.key().endsWith(".outcome"))).hasSize(50);
        assertThat(evidence.item("run.order.O-059.outcome").orElseThrow().text()).isEqualTo("UNFINISHED");
    }

    @Test
    void theHashIsDeterministicAndFollowsTheData() {
        String first = EvidenceJson.hash(EvidenceBuilder.build(run(), baseline(), comparison(run(), baseline())));
        String second = EvidenceJson.hash(EvidenceBuilder.build(run(), baseline(), comparison(run(), baseline())));
        String withoutBaseline = EvidenceJson.hash(EvidenceBuilder.build(run(), null, null));

        assertThat(first).hasSize(64).isEqualTo(second).isNotEqualTo(withoutBaseline);
    }

    @Test
    void thePromptFormLeavesOutInternalIdentifiers() {
        String json = EvidenceJson.forPrompt(EvidenceBuilder.build(run(), null, null));

        assertThat(json).contains("\"run.summary.elapsedSeconds\"", "\"52.0 hours\"", "\"value\":\"0.962\"")
                .doesNotContain("sourceRunId", "stageId", EvidenceFixtures.POLISHING.toString());
    }

    @Test
    void formatterShowsDifferencesSignedAndFractionsInPoints() {
        assertThat(EvidenceFormatter.display(new BigDecimal("-0.075"), "fraction", "INR", true))
                .containsExactly("-7.5 percentage points", "-8 percentage points");
        assertThat(EvidenceFormatter.display(new BigDecimal("1800"), "seconds", "INR", false))
                .containsExactly("0.5 hours", "30 minutes", "1 hours");
        assertThat(EvidenceFormatter.display(new BigDecimal("12.5"), "percent", "INR", true))
                .containsExactly("+12.5%", "+13%");
        assertThat(EvidenceFormatter.display(null, "seconds", "INR", false)).isEmpty();
    }
}
