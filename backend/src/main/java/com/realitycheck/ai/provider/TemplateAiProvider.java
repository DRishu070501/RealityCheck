package com.realitycheck.ai.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.realitycheck.ai.domain.InsightContent;
import com.realitycheck.ai.domain.InsightContent.Recommendation;
import com.realitycheck.ai.domain.InsightType;
import com.realitycheck.ai.evidence.Evidence;
import com.realitycheck.ai.evidence.Evidence.EvidenceItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The built-in analyst. It selects facts by key and fills fixed sentences, inserting only
 * evidence display strings and values, so it never states a number that is not in the evidence
 * and needs no network. It answers when no language model is configured and whenever one fails.
 */
public final class TemplateAiProvider implements AiProvider {

    public static final String NAME = "template";

    private static final BigDecimal SATURATED = new BigDecimal("0.85");
    private static final BigDecimal NEAR_FULL = new BigDecimal("0.95");
    private static final BigDecimal UNDER_USED = new BigDecimal("0.40");
    private static final int MAX_LISTED_ORDERS = 5;
    private static final int TEXT_LIMIT = 600;

    private static final Pattern WHAT_IF = Pattern.compile(
            "(?i)\\b(what if|what would|what happens if|if we|if i|should we|would it|would adding|could we"
                    + "|adding|hire|hiring|buy|buying|double|halve|another|extra|more (machines?|people|staff|workers"
                    + "|operators|shifts?))\\b");
    private static final Pattern COMPARISON = Pattern.compile("(?i)compar|differ|baseline|better|worse|versus|\\bvs\\b");
    private static final Pattern BOTTLENECK = Pattern.compile("(?i)bottleneck|slow|constrain|limit|holding|hold up");
    private static final Pattern UNDER_USED_Q = Pattern.compile(
            "(?i)under-?util|under-?used|idle|unused|spare|least used|low utili|not used");
    private static final Pattern WAIT = Pattern.compile("(?i)wait|queue|stuck|longest");
    private static final Pattern DELAY = Pattern.compile("(?i)delay|late|due|on[- ]time|deliver");
    private static final Pattern COST = Pattern.compile("(?i)cost|expens|money|spend|price|budget|rupee|inr");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean languageModel() {
        return false;
    }

    @Override
    public AiResponse complete(AiRequest request) {
        InsightContent content = content(request.type(), request.evidence(), request.question());
        try {
            return new AiResponse(JSON.writeValueAsString(content), null, null, null);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Template insight could not be written", ex);
        }
    }

    public InsightContent content(InsightType type, Evidence evidence, String question) {
        return new Writer(evidence).write(type, question);
    }

    /** Builds one insight; collects the keys it cites. */
    private static final class Writer {

        private final Evidence ev;
        private final Set<String> refs = new LinkedHashSet<>();

        Writer(Evidence evidence) {
            this.ev = evidence;
        }

        InsightContent write(InsightType type, String question) {
            boolean comparison = ev.baseline() != null && ev.has("delta.bottleneck.change");
            String summary;
            List<String> keyChanges = comparison ? deltaChanges() : appliedChanges();
            List<String> risks = risks(comparison);
            String bottleneck = bottleneckExplanation("run");
            List<Recommendation> recs;
            switch (type) {
                case BOTTLENECK -> {
                    summary = bottleneckSummary();
                    recs = recommendations(false, false, true);
                }
                case RECOMMENDATIONS -> {
                    summary = "Suggestions drawn from the simulated results of " + quoted(ev.run().scenarioName())
                            + ". Each one names the evidence it rests on; simulate a suggestion as a scenario before"
                            + " acting on it.";
                    recs = recommendations(true, comparison, true);
                }
                case DELAY_ANALYSIS -> {
                    summary = delaySummary();
                    risks = delayRisks();
                    recs = recommendations(true, false, false);
                }
                case COMPARISON_SUMMARY -> {
                    summary = comparison ? comparisonSummary() : runSummary();
                    recs = recommendations(false, comparison, true);
                }
                case EXECUTIVE_REPORT -> {
                    summary = comparison ? join(runSummary(), comparisonSummary()) : runSummary();
                    recs = recommendations(true, comparison, true);
                }
                case ANSWER -> {
                    return answer(question, comparison, keyChanges, risks, bottleneck);
                }
                default -> {
                    summary = runSummary();
                    recs = recommendations(true, comparison, true);
                }
            }
            return content(summary, keyChanges, risks, bottleneck, recs);
        }

        private InsightContent content(String summary, List<String> keyChanges, List<String> risks,
                String bottleneck, List<Recommendation> recs) {
            List<String> limitations = new ArrayList<>();
            limitations.add("Written by the built-in analyst from fixed sentences; no language model was used.");
            if (ev.truncated()) {
                limitations.add("Some order details were left out of the evidence to keep it short.");
            }
            if (!"COMPLETED".equals(ev.run().terminationReason())) {
                limitations.add("The simulation stopped before all work finished, so totals cover only the work done.");
            }
            return new InsightContent(clip(summary, 1200), cap(keyChanges), cap(risks), clip(bottleneck, TEXT_LIMIT),
                    recs.stream().limit(8).toList(), refs.stream().limit(16).toList(), assumptions(),
                    cap(limitations));
        }

        // ---- Summaries -----------------------------------------------------------------------

        private String runSummary() {
            StringBuilder s = new StringBuilder("In ").append(quoted(ev.run().scenarioName())).append(", ");
            String elapsed = show("run.summary.elapsedSeconds");
            if ("COMPLETED".equals(ev.run().terminationReason())) {
                s.append("the simulation finishes all work ").append(elapsed).append(" after it starts");
            } else {
                s.append("the simulation stops (").append(ev.run().terminationReason()).append(") with work unfinished, ")
                        .append(elapsed).append(" after it starts");
            }
            String stones = show("run.summary.outputStones");
            String cost = show("run.cost.total");
            if (stones != null && cost != null) {
                s.append(", producing ").append(stones).append(" at a total cost of ").append(cost);
            }
            s.append('.');
            String delayed = plain("run.summary.ordersDelayed");
            String total = plain("run.summary.ordersTotal");
            if (delayed != null && total != null) {
                s.append(' ').append(delayed).append(" of ").append(total).append(" orders are delayed.");
            }
            String primary = text("run.bottleneck.primaryStage");
            if (primary != null && !"None".equals(primary)) {
                s.append(" The primary bottleneck is ").append(primary).append('.');
            }
            return s.toString();
        }

        private String comparisonSummary() {
            StringBuilder s = new StringBuilder("Compared with ").append(quoted(ev.baseline().scenarioName()))
                    .append(", ").append(quoted(ev.run().scenarioName())).append(" changes");
            List<String> parts = new ArrayList<>();
            change("elapsedSeconds", "completion time").ifPresent(parts::add);
            change("cost.total", "total cost").ifPresent(parts::add);
            change("ordersDelayed", "delayed orders").ifPresent(parts::add);
            if (parts.isEmpty()) {
                return "Compared with " + quoted(ev.baseline().scenarioName()) + ", the headline results of "
                        + quoted(ev.run().scenarioName()) + " are unchanged.";
            }
            s.append(' ').append(String.join(", ", parts)).append('.');
            String move = text("delta.bottleneck.change");
            if ("MOVED".equals(move)) {
                s.append(" The bottleneck moves from ").append(text("baseline.bottleneck.primaryStage")).append(" to ")
                        .append(text("run.bottleneck.primaryStage")).append('.');
                cite("delta.bottleneck.change");
            }
            return s.toString();
        }

        /** "completion time by -12.0 hours (-25.0%)", or empty when unchanged. */
        private Optional<String> change(String metric, String label) {
            String key = "delta.summary." + metric + ".absolute";
            Optional<EvidenceItem> abs = ev.item(key);
            if (abs.isEmpty() || abs.get().value().signum() == 0) {
                return Optional.empty();
            }
            cite(key);
            String percent = show("delta.summary." + metric + ".percent");
            return Optional.of(label + " by " + abs.get().shown() + (percent == null ? "" : " (" + percent + ")"));
        }

        private String bottleneckSummary() {
            String primary = text("run.bottleneck.primaryStage");
            String explanation = text("run.bottleneck.explanation");
            if (primary == null || "None".equals(primary)) {
                return explanation != null ? explanation : "No stage qualifies as a clear bottleneck in this simulation.";
            }
            return "The primary bottleneck is " + primary + "." + (explanation == null ? "" : " " + explanation);
        }

        private String delaySummary() {
            String delayed = plain("run.summary.ordersDelayed");
            String total = plain("run.summary.ordersTotal");
            if (delayed == null || total == null) {
                return "The evidence has no order counts for this run.";
            }
            if (ev.item("run.summary.ordersDelayed").get().value().signum() == 0) {
                return "No order is delayed in this simulation: all " + total + " orders finish by their due date or"
                        + " have none.";
            }
            String rate = show("run.summary.onTimeDeliveryRate");
            return delayed + " of " + total + " orders are delayed in this simulation"
                    + (rate == null ? "." : "; the on-time delivery rate is " + rate + ".");
        }

        // ---- Sections ------------------------------------------------------------------------

        private List<String> appliedChanges() {
            if (ev.run().appliedChanges().isEmpty()) {
                return List.of("No changes: this run simulates the factory as recorded.");
            }
            return ev.run().appliedChanges().stream().map(c -> "Simulated change: " + c).toList();
        }

        private List<String> deltaChanges() {
            List<String> out = new ArrayList<>();
            String[][] metrics = {
                    {"elapsedSeconds", "summary.elapsedSeconds", "Completion time"},
                    {"cost.total", "cost.total", "Total cost"},
                    {"cost.perOutputStone", "cost.perOutputStone", "Cost per output stone"},
                    {"throughputStonesPerHour", "summary.throughputStonesPerHour", "Throughput"},
                    {"ordersDelayed", "summary.ordersDelayed", "Orders delayed"},
                    {"onTimeDeliveryRate", "summary.onTimeDeliveryRate", "On-time delivery rate"}};
            for (String[] m : metrics) {
                String absKey = "delta.summary." + m[0] + ".absolute";
                Optional<EvidenceItem> abs = ev.item(absKey);
                String before = show("baseline." + m[1]);
                String after = show("run." + m[1]);
                if (abs.isEmpty() || before == null || after == null || abs.get().value().signum() == 0) {
                    continue;
                }
                cite(absKey);
                String percent = show("delta.summary." + m[0] + ".percent");
                String direction = text("delta.summary." + m[0] + ".direction");
                out.add(m[2] + ": " + before + " to " + after + " (" + abs.get().shown()
                        + (percent == null ? "" : ", " + percent) + ")"
                        + (direction == null ? "" : ", " + direction.toLowerCase(Locale.ROOT)) + ".");
            }
            String change = text("delta.bottleneck.change");
            if (change != null && !"SAME".equals(change) && !"NONE".equals(change)) {
                out.add("Primary bottleneck: " + text("baseline.bottleneck.primaryStage") + " to "
                        + text("run.bottleneck.primaryStage") + " (" + change + ").");
                cite("delta.bottleneck.change");
            }
            if (out.isEmpty()) {
                out.add("No headline result differs from the baseline.");
            }
            return out;
        }

        private List<String> risks(boolean comparison) {
            List<String> out = new ArrayList<>();
            delayedOrdersSentence().ifPresent(out::add);
            if (!"COMPLETED".equals(ev.run().terminationReason())) {
                out.add("The simulation stopped (" + ev.run().terminationReason() + ") with work unfinished.");
            }
            for (EvidenceItem util : poolUtilizations()) {
                if (util.value().compareTo(NEAR_FULL) >= 0) {
                    out.add(poolName(util) + " is busy " + util.shown()
                            + " of its available time, leaving almost no slack for breakdowns or absences.");
                    cite(util.key());
                }
            }
            if (comparison) {
                for (EvidenceItem warning : ev.withPrefix("delta.warning.")) {
                    out.add(warning.text());
                }
                for (EvidenceItem direction : ev.withPrefix("delta.summary.")) {
                    if (direction.key().endsWith(".direction") && "WORSENED".equals(direction.text())) {
                        String absKey = direction.key().replace(".direction", ".absolute");
                        ev.item(absKey).ifPresent(abs -> {
                            out.add(stripSuffix(abs.label()) + " worsens by " + abs.shown() + ".");
                            cite(absKey);
                        });
                    }
                }
            }
            return out;
        }

        private List<String> delayRisks() {
            List<String> out = new ArrayList<>();
            for (EvidenceItem outcome : delayedOrders()) {
                String number = orderNumber(outcome);
                String base = outcome.key().substring(0, outcome.key().length() - ".outcome".length());
                String due = text(base + ".dueAt");
                String completes = text(base + ".completesAt");
                out.add(number + ": " + outcome.text() + (due == null ? "" : ", due " + due)
                        + (completes == null ? "" : ", simulated completion " + completes) + ".");
                cite(outcome.key());
            }
            if (out.isEmpty()) {
                out.add("No order is late or unfinished in this simulation.");
            }
            return out;
        }

        private Optional<String> delayedOrdersSentence() {
            List<EvidenceItem> delayed = delayedOrders();
            if (delayed.isEmpty()) {
                return Optional.empty();
            }
            cite("run.summary.ordersDelayed");
            List<String> named = delayed.stream().limit(MAX_LISTED_ORDERS)
                    .map(o -> orderNumber(o) + " (" + o.text() + ")").toList();
            return Optional.of("Delayed orders: " + String.join(", ", named)
                    + (delayed.size() > MAX_LISTED_ORDERS ? " and others." : "."));
        }

        private String bottleneckExplanation(String prefix) {
            String primary = text(prefix + ".bottleneck.primaryStage");
            String explanation = text(prefix + ".bottleneck.explanation");
            if (primary == null || "None".equals(primary)) {
                return explanation == null ? "No stage qualifies as a clear bottleneck." : explanation;
            }
            cite(prefix + ".bottleneck.primaryStage");
            String stage = stageKey(prefix, ev.item(prefix + ".bottleneck.primaryStage").get());
            StringBuilder s = new StringBuilder(primary).append(" is the primary bottleneck.");
            if (stage != null) {
                String pool = text(stage + ".limitingPool");
                String util = show(stage + ".limitingUtilization");
                if (pool != null && util != null) {
                    s.append(" Its busiest required pool, ").append(pool).append(", is busy ").append(util)
                            .append(" of the available time.");
                    cite(stage + ".limitingUtilization");
                }
                String wait = show(stage + ".meanWaitWorkingSeconds");
                String queue = show(stage + ".meanQueueLength");
                if (wait != null && queue != null) {
                    s.append(" Jobs wait ").append(wait).append(" there on average, and the queue averages ")
                            .append(queue).append('.');
                    cite(stage + ".meanWaitWorkingSeconds");
                }
            }
            return s.toString();
        }

        private List<Recommendation> recommendations(boolean includeDelays, boolean includeTradeOff,
                boolean includeCapacity) {
            List<Recommendation> out = new ArrayList<>();
            String primaryKey = "run.bottleneck.primaryStage";
            String primary = text(primaryKey);
            if (includeCapacity && primary != null && !"None".equals(primary)) {
                String stage = stageKey("run", ev.item(primaryKey).get());
                Optional<EvidenceItem> util = stage == null ? Optional.empty()
                        : ev.item(stage + ".limitingUtilization");
                String pool = stage == null ? null : text(stage + ".limitingPool");
                if (util.isPresent() && pool != null && util.get().value().compareTo(SATURATED) >= 0) {
                    out.add(new Recommendation("Simulate more capacity for " + pool,
                            primary + " is the primary bottleneck and " + pool + " is busy " + util.get().shown()
                                    + ". Create a scenario that adds units to it, run it and compare the result.",
                            List.of(primaryKey, util.get().key())));
                }
            }
            if (includeCapacity) {
                poolUtilizations().stream()
                        .filter(u -> u.value().compareTo(UNDER_USED) < 0)
                        .sorted(Comparator.comparing(EvidenceItem::value))
                        .limit(2)
                        .forEach(u -> out.add(new Recommendation("Review the allocation of " + poolName(u),
                                poolName(u) + " is busy only " + u.shown()
                                        + " of its available time; some of its capacity may be better used elsewhere."
                                        + " Simulate the reallocation before changing anything.",
                                List.of(u.key()))));
            }
            if (includeDelays) {
                List<EvidenceItem> delayed = delayedOrders();
                if (!delayed.isEmpty() && ev.has("run.summary.ordersDelayed")) {
                    List<String> refs = new ArrayList<>();
                    refs.add("run.summary.ordersDelayed");
                    delayed.stream().limit(3).map(EvidenceItem::key).forEach(refs::add);
                    out.add(new Recommendation("Look at the delayed orders first",
                            plain("run.summary.ordersDelayed") + " orders finish late or not at all, including "
                                    + String.join(", ", delayed.stream().limit(3).map(this::orderNumber).toList())
                                    + ". Simulate an extra shift or more capacity at the bottleneck to see whether"
                                    + " they would finish on time.",
                            refs));
                }
            }
            if (includeTradeOff) {
                Optional<EvidenceItem> time = ev.item("delta.summary.elapsedSeconds.absolute");
                Optional<EvidenceItem> perStone = ev.item("delta.summary.cost.perOutputStone.absolute");
                if (time.isPresent() && perStone.isPresent() && time.get().value().signum() < 0
                        && perStone.get().value().signum() > 0) {
                    out.add(new Recommendation("Weigh speed against cost",
                            "The scenario changes completion time by " + time.get().shown()
                                    + " but cost per output stone by " + perStone.get().shown()
                                    + ". Decide whether finishing sooner is worth the higher unit cost.",
                            List.of(time.get().key(), perStone.get().key())));
                } else if (time.isPresent() && perStone.isPresent() && time.get().value().signum() <= 0
                        && perStone.get().value().signum() <= 0
                        && (time.get().value().signum() < 0 || perStone.get().value().signum() < 0)) {
                    out.add(new Recommendation("Consider this scenario further",
                            "It changes completion time by " + time.get().shown() + " and cost per output stone by "
                                    + perStone.get().shown() + ", with neither worse than the baseline. Check its"
                                    + " assumptions against what is practical before acting.",
                            List.of(time.get().key(), perStone.get().key())));
                }
            }
            if (out.isEmpty()) {
                List<String> refs = new ArrayList<>();
                if (ev.has("run.summary.ordersDelayed")) {
                    refs.add("run.summary.ordersDelayed");
                }
                if (ev.has("run.summary.machineUtilization")) {
                    refs.add("run.summary.machineUtilization");
                }
                if (refs.isEmpty()) {
                    refs.add(ev.items().get(0).key());
                }
                out.add(new Recommendation("No change stands out",
                        "The rules of the built-in analyst find no saturated pool, under-used pool or delayed order"
                                + " that calls for action here. Simulate the changes you are considering and compare"
                                + " them with this run.",
                        refs));
            }
            return out;
        }

        private List<String> assumptions() {
            List<String> out = new ArrayList<>();
            out.add("Figures are simulated estimates from recorded history and configuration under the simulation's"
                    + " fixed rules; they are not guarantees.");
            out.add("The simulation starts at " + ev.run().forkAtDisplay() + " and uses algorithm "
                    + ev.run().algorithmVersion() + ".");
            if (ev.baseline() != null) {
                out.add("Differences are " + quoted(ev.run().scenarioName()) + " minus "
                        + quoted(ev.baseline().scenarioName()) + ".");
            }
            return out;
        }

        // ---- Questions -----------------------------------------------------------------------

        private InsightContent answer(String question, boolean comparison, List<String> keyChanges,
                List<String> risks, String bottleneck) {
            String q = question == null ? "" : question;
            if (WHAT_IF.matcher(q).find() && !COMPARISON.matcher(q).find()) {
                return content("That change has not been simulated, so there is no result to report for it. Create a"
                        + " scenario with the change in Scenario Lab, run it and compare it with this run; the"
                        + " built-in analyst does not estimate the effect of changes that were not simulated.",
                        appliedChanges(), List.of(), bottleneck, List.of());
            }
            if (COMPARISON.matcher(q).find()) {
                if (comparison) {
                    return content(comparisonSummary(), keyChanges, risks, bottleneck,
                            recommendations(false, true, false));
                }
                return content("No baseline run was chosen, so there is nothing to compare with. Pick a baseline run"
                        + " and ask again.", appliedChanges(), List.of(), bottleneck, List.of());
            }
            if (BOTTLENECK.matcher(q).find()) {
                return content(bottleneckSummary(), keyChanges, List.of(), bottleneck,
                        recommendations(false, false, true));
            }
            if (UNDER_USED_Q.matcher(q).find()) {
                Optional<EvidenceItem> lowest = poolUtilizations().stream().min(Comparator.comparing(EvidenceItem::value));
                if (lowest.isEmpty()) {
                    return content("The evidence has no pool utilization for this run.", keyChanges, List.of(),
                            bottleneck, List.of());
                }
                cite(lowest.get().key());
                return content("The least used pool is " + poolName(lowest.get()) + ", busy " + lowest.get().shown()
                        + " of its available time.", keyChanges, List.of(), bottleneck,
                        recommendations(false, false, true));
            }
            if (WAIT.matcher(q).find()) {
                Optional<EvidenceItem> longest = ev.withPrefix("run.stage.").stream()
                        .filter(i -> i.key().endsWith(".meanWaitWorkingSeconds"))
                        .max(Comparator.comparing(EvidenceItem::value));
                if (longest.isEmpty()) {
                    return content("The evidence has no waiting times for this run.", keyChanges, List.of(),
                            bottleneck, List.of());
                }
                cite(longest.get().key());
                String key = longest.get().key();
                String stage = Optional.ofNullable(text(key.substring(0, key.lastIndexOf('.')) + ".name"))
                        .orElse("one stage");
                return content("Work waits longest at " + stage + ": " + longest.get().shown()
                        + " per job on average.", keyChanges, List.of(), bottleneck,
                        recommendations(false, false, true));
            }
            if (DELAY.matcher(q).find()) {
                return content(delaySummary(), keyChanges, delayRisks(), bottleneck,
                        recommendations(true, false, false));
            }
            if (COST.matcher(q).find()) {
                String total = show("run.cost.total");
                String perStone = show("run.cost.perOutputStone");
                if (total == null) {
                    return content("The evidence has no cost for this run.", keyChanges, List.of(), bottleneck,
                            List.of());
                }
                cite("run.cost.total");
                StringBuilder s = new StringBuilder("The total simulated cost is ").append(total);
                if (perStone != null) {
                    s.append(", or ").append(perStone);
                    cite("run.cost.perOutputStone");
                }
                List<String> parts = new ArrayList<>();
                for (String[] part : new String[][] {{"run.cost.employee", "employees"}, {"run.cost.machine",
                        "machines"}, {"run.cost.fixed", "fixed stage costs"}}) {
                    String shown = show(part[0]);
                    if (shown != null) {
                        parts.add(shown + " for " + part[1]);
                    }
                }
                if (!parts.isEmpty()) {
                    s.append(": ").append(String.join(", ", parts));
                }
                s.append('.');
                if (comparison) {
                    change("cost.total", "Against the baseline, total cost changes")
                            .ifPresent(c -> s.append(' ').append(c).append('.'));
                }
                return content(s.toString(), keyChanges, List.of(), bottleneck, List.of());
            }
            return content("The built-in analyst answers questions about the bottleneck, where work waits longest,"
                    + " under-used resources, delayed orders, cost and, with a baseline, the comparison. This"
                    + " question is outside those topics. The evidence covers the run's summary, cost, stages,"
                    + " resource pools, orders and applied changes.", keyChanges, List.of(), bottleneck, List.of());
        }

        // ---- Helpers -------------------------------------------------------------------------

        private List<EvidenceItem> poolUtilizations() {
            return ev.withPrefix("run.resource.").stream()
                    .filter(i -> i.key().endsWith(".utilization") && i.value() != null)
                    .toList();
        }

        private List<EvidenceItem> delayedOrders() {
            return ev.withPrefix("run.order.").stream()
                    .filter(i -> i.key().endsWith(".outcome")
                            && ("LATE".equals(i.text()) || "UNFINISHED".equals(i.text())))
                    .toList();
        }

        private String orderNumber(EvidenceItem outcome) {
            // Label: "Order <number>: outcome (...)"
            String label = outcome.label();
            return label.substring("Order ".length(), label.indexOf(':'));
        }

        /** The pool's name item, beside its utilization item. */
        private String poolName(EvidenceItem poolItem) {
            String base = poolItem.key().substring(0, poolItem.key().lastIndexOf('.'));
            return Optional.ofNullable(text(base + ".name")).orElse("This pool");
        }

        /** The stage key prefix ({@code run.stage.CODE}) of a bottleneck item, found by stage id. */
        private String stageKey(String prefix, EvidenceItem primary) {
            if (primary.stageId() == null) {
                return null;
            }
            return ev.withPrefix(prefix + ".stage.").stream()
                    .filter(i -> primary.stageId().equals(i.stageId()))
                    .map(i -> i.key().substring(0, i.key().lastIndexOf('.')))
                    .findFirst().orElse(null);
        }

        private String show(String key) {
            return ev.item(key).map(EvidenceItem::shown).orElse(null);
        }

        private String text(String key) {
            return ev.item(key).map(EvidenceItem::text).orElse(null);
        }

        /** The stored value as written, for counts in running text. */
        private String plain(String key) {
            return ev.item(key).map(i -> i.value() == null ? null : i.value().stripTrailingZeros().toPlainString())
                    .orElse(null);
        }

        private void cite(String key) {
            if (ev.has(key)) {
                refs.add(key);
            }
        }

        private static String stripSuffix(String label) {
            int colon = label.indexOf(':');
            return colon < 0 ? label : label.substring(0, colon);
        }

        private static String quoted(String name) {
            return "“" + name + "”";
        }

        private static String join(String first, String second) {
            return first + " " + second;
        }

        private static List<String> cap(List<String> values) {
            return values.stream().map(v -> clip(v, TEXT_LIMIT)).limit(8).toList();
        }

        /** Cuts at a sentence end within the limit; text never needs this in practice. */
        private static String clip(String text, int limit) {
            if (text == null || text.length() <= limit) {
                return text;
            }
            int cut = text.lastIndexOf(". ", limit - 1);
            return cut > 0 ? text.substring(0, cut + 1) : text.substring(0, limit);
        }
    }
}
