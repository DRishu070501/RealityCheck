package com.realitycheck.ai.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.realitycheck.ai.domain.InsightContent;
import com.realitycheck.ai.domain.InsightContent.Recommendation;
import com.realitycheck.ai.evidence.Evidence;
import com.realitycheck.ai.evidence.Evidence.EvidenceItem;
import com.realitycheck.ai.evidence.Evidence.RunFacts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks a provider's output before anyone sees it: strict shape and lengths, metric references
 * that exist, and every number in the text present in the evidence. This is what stops a model
 * from inventing a figure; a response that fails is never shown.
 */
public final class InsightValidator {

    public static final int SUMMARY_MAX = 1200;
    public static final int TEXT_MAX = 600;
    public static final int LIST_MAX = 8;
    /** References are not prose: an answer may rest on more keys than it has sentences. */
    public static final int REFS_MAX = 16;

    /** A number as written in prose: optional sign, thousands separators, decimals, percent. */
    static final Pattern NUMBER = Pattern.compile("[-+]?\\d[\\d,]*(\\.\\d+)?%?");
    private static final Pattern SAVING_WORD = Pattern.compile("(?i)\\b(sav|reduc)\\w*");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;])\\s+");
    private static final Pattern FENCE = Pattern.compile("(?s)^```(?:json)?\\s*(.*?)\\s*```$");

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .build();

    /** @param token the offending number or reference, when there is one */
    public record ValidationError(String code, String field, String token, String message) {

        public static final String MALFORMED_JSON = "MALFORMED_JSON";
        public static final String SCHEMA = "SCHEMA";
        public static final String UNKNOWN_METRIC_REF = "UNKNOWN_METRIC_REF";
        public static final String UNSUPPORTED_NUMBER = "UNSUPPORTED_NUMBER";
        public static final String MISSING_METRIC_REF = "MISSING_METRIC_REF";
        public static final String UNSUPPORTED_SAVING_CLAIM = "UNSUPPORTED_SAVING_CLAIM";
    }

    /** @param content null when there are errors */
    public record Result(InsightContent content, List<ValidationError> errors) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private InsightValidator() {
    }

    public static Result validate(String rawJson, Evidence evidence) {
        if (rawJson == null || rawJson.isBlank()) {
            return failed(new ValidationError(ValidationError.MALFORMED_JSON, null, null, "The response was empty."));
        }
        String json = rawJson.strip();
        Matcher fence = FENCE.matcher(json);
        if (fence.matches()) {
            json = fence.group(1);
        }
        InsightContent content;
        try {
            STRICT.readTree(json);
        } catch (Exception ex) {
            return failed(new ValidationError(ValidationError.MALFORMED_JSON, null, null,
                    "The response is not valid JSON."));
        }
        try {
            content = STRICT.readValue(json, InsightContent.class);
        } catch (Exception ex) {
            return failed(new ValidationError(ValidationError.SCHEMA, null, null,
                    "The JSON does not match the schema: every field is required, no others are allowed. "
                            + firstLine(ex.getMessage())));
        }
        return validate(content, evidence);
    }

    public static Result validate(InsightContent content, Evidence evidence) {
        if (content.metricRefs() != null && content.recommendations() != null
                && content.recommendations().stream().allMatch(r -> r != null && r.metricRefs() != null)) {
            content = normaliseRefs(content, evidence);
        }
        List<ValidationError> errors = new ArrayList<>();
        Map<String, String> texts = texts(content, errors);
        if (!errors.isEmpty()) {
            return new Result(null, List.copyOf(errors));
        }
        Set<String> keys = evidence.items().stream().map(EvidenceItem::key).collect(Collectors.toSet());
        checkRefs("metricRefs", content.metricRefs(), keys, errors);
        for (int i = 0; i < content.recommendations().size(); i++) {
            Recommendation rec = content.recommendations().get(i);
            String field = "recommendations[" + i + "].metricRefs";
            if (rec.metricRefs().isEmpty()) {
                errors.add(new ValidationError(ValidationError.MISSING_METRIC_REF, field, null,
                        "Each recommendation must cite at least one evidence key."));
            }
            checkRefs(field, rec.metricRefs(), keys, errors);
        }
        Allowed allowed = allowed(evidence);
        texts.forEach((field, text) -> {
            String masked = allowed.mask(text);
            Matcher m = NUMBER.matcher(masked);
            while (m.find()) {
                String token = m.group();
                BigDecimal number = parse(token);
                if (number != null && !allowed.numbers.contains(number.abs())) {
                    errors.add(new ValidationError(ValidationError.UNSUPPORTED_NUMBER, field, token,
                            "The number " + token + " is not in the evidence. Quote only evidence values or display"
                                    + " strings; do not calculate, convert or round."));
                }
            }
            checkSavings(field, masked, allowed, evidence, errors);
        });
        return errors.isEmpty() ? new Result(content, List.of()) : new Result(null, List.copyOf(errors));
    }

    // ---- Shape -------------------------------------------------------------------------------

    /** Every text field by path, after shape and length checks. */
    private static Map<String, String> texts(InsightContent c, List<ValidationError> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        text(out, errors, "summary", c.summary(), SUMMARY_MAX, true);
        text(out, errors, "bottleneckExplanation", c.bottleneckExplanation(), TEXT_MAX, false);
        list(out, errors, "keyChanges", c.keyChanges());
        list(out, errors, "risks", c.risks());
        list(out, errors, "assumptions", c.assumptions());
        list(out, errors, "limitations", c.limitations());
        if (c.metricRefs().size() > REFS_MAX) {
            errors.add(schema("metricRefs", "metricRefs has more than " + REFS_MAX + " items."));
        }
        if (c.metricRefs().stream().anyMatch(r -> r == null)) {
            errors.add(schema("metricRefs", "metricRefs must not contain null."));
        }
        size(errors, "recommendations", c.recommendations());
        for (int i = 0; i < c.recommendations().size(); i++) {
            Recommendation rec = c.recommendations().get(i);
            String field = "recommendations[" + i + "]";
            if (rec == null || rec.title() == null || rec.rationale() == null || rec.metricRefs() == null
                    || rec.metricRefs().stream().anyMatch(r -> r == null)) {
                errors.add(schema(field, "A recommendation needs title, rationale and metricRefs."));
                continue;
            }
            text(out, errors, field + ".title", rec.title(), TEXT_MAX, true);
            text(out, errors, field + ".rationale", rec.rationale(), TEXT_MAX, true);
            size(errors, field + ".metricRefs", rec.metricRefs());
        }
        return out;
    }

    private static void text(Map<String, String> out, List<ValidationError> errors, String field, String value,
            int max, boolean required) {
        if (required && value.isBlank()) {
            errors.add(schema(field, field + " must not be empty."));
        }
        if (value.length() > max) {
            errors.add(schema(field, field + " is longer than " + max + " characters."));
        }
        out.put(field, value);
    }

    private static void list(Map<String, String> out, List<ValidationError> errors, String field, List<String> values) {
        size(errors, field, values);
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value == null) {
                errors.add(schema(field, field + " must not contain null."));
                continue;
            }
            text(out, errors, field + "[" + i + "]", value, TEXT_MAX, true);
        }
    }

    private static void size(List<ValidationError> errors, String field, List<?> values) {
        if (values.size() > LIST_MAX) {
            errors.add(schema(field, field + " has more than " + LIST_MAX + " items."));
        }
    }

    private static void checkRefs(String field, List<String> refs, Set<String> keys, List<ValidationError> errors) {
        for (String ref : refs) {
            if (!keys.contains(ref)) {
                errors.add(new ValidationError(ValidationError.UNKNOWN_METRIC_REF, field, ref,
                        "\"" + ref + "\" is not an evidence key. Use keys exactly as given."));
            }
        }
    }

    /**
     * A reference to a field of an item ({@code run.cost.total.display}) means the item itself.
     * Rewriting it changes no number; any other unknown reference is still rejected.
     */
    private static InsightContent normaliseRefs(InsightContent c, Evidence evidence) {
        Set<String> keys = evidence.items().stream().map(EvidenceItem::key).collect(Collectors.toSet());
        java.util.function.UnaryOperator<List<String>> fix = refs -> refs.stream().map(ref -> {
            if (ref == null || keys.contains(ref)) {
                return ref;
            }
            for (String suffix : List.of(".display", ".value", ".text", ".label", ".unit")) {
                if (ref.endsWith(suffix) && keys.contains(ref.substring(0, ref.length() - suffix.length()))) {
                    return ref.substring(0, ref.length() - suffix.length());
                }
            }
            return ref;
        }).distinct().toList();
        return new InsightContent(c.summary(), c.keyChanges(), c.risks(), c.bottleneckExplanation(),
                c.recommendations().stream()
                        .map(r -> new Recommendation(r.title(), r.rationale(), fix.apply(r.metricRefs()))).toList(),
                fix.apply(c.metricRefs()), c.assumptions(), c.limitations());
    }

    // ---- Numbers -----------------------------------------------------------------------------

    /**
     * Numbers the text may contain, and evidence text it may quote verbatim (order numbers, stage
     * and scenario names, change summaries), which is masked before numbers are read.
     */
    private record Allowed(TreeSet<BigDecimal> numbers, TreeSet<BigDecimal> costDifferences, List<String> quotable) {

        String mask(String text) {
            String out = text;
            for (String quote : quotable) {
                out = out.replace(quote, " ");
            }
            return out;
        }
    }

    private static Allowed allowed(Evidence evidence) {
        TreeSet<BigDecimal> numbers = new TreeSet<>();
        TreeSet<BigDecimal> costs = new TreeSet<>();
        TreeSet<String> quotable = new TreeSet<>(Comparator.comparingInt(String::length).reversed()
                .thenComparing(Comparator.naturalOrder()));
        for (EvidenceItem item : evidence.items()) {
            quotable.add(item.key());
            boolean cost = item.key().startsWith("delta.") && item.key().contains("cost");
            if (item.value() != null) {
                numbers.add(item.value().abs());
                if (cost) {
                    costs.add(item.value().abs());
                }
            }
            for (String display : item.display()) {
                numbersIn(display).forEach(n -> {
                    numbers.add(n);
                    if (cost) {
                        costs.add(n);
                    }
                });
            }
            if (item.text() != null) {
                quotable.add(item.text());
                numbers.addAll(numbersIn(item.text()));
            }
        }
        Stream.of(evidence.run(), evidence.baseline()).filter(f -> f != null).forEach(f -> facts(f, numbers, quotable));
        quotable.removeIf(String::isBlank);
        return new Allowed(numbers, costs, List.copyOf(quotable));
    }

    private static void facts(RunFacts facts, Set<BigDecimal> numbers, Set<String> quotable) {
        if (facts.scenarioName() != null) {
            quotable.add(facts.scenarioName());
        }
        if (facts.algorithmVersion() != null) {
            quotable.add(facts.algorithmVersion());
        }
        if (facts.forkAtDisplay() != null) {
            quotable.add(facts.forkAtDisplay());
            numbers.addAll(numbersIn(facts.forkAtDisplay()));
        }
        for (String change : facts.appliedChanges()) {
            quotable.add(change);
            numbers.addAll(numbersIn(change));
        }
    }

    static List<BigDecimal> numbersIn(String text) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            BigDecimal n = parse(m.group());
            if (n != null) {
                out.add(n.abs());
            }
        }
        return out;
    }

    static BigDecimal parse(String token) {
        String cleaned = token.replace(",", "").replace("%", "").replace("+", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---- Savings -----------------------------------------------------------------------------

    /**
     * A sentence that talks about saving or reducing and names a money amount may only use an
     * amount that is a cost difference in the evidence.
     */
    private static void checkSavings(String field, String text, Allowed allowed, Evidence evidence,
            List<ValidationError> errors) {
        Pattern money = moneyPattern(evidence.run().currencyCode());
        for (String sentence : SENTENCE_END.split(text)) {
            if (!SAVING_WORD.matcher(sentence).find()) {
                continue;
            }
            Matcher m = money.matcher(sentence);
            while (m.find()) {
                String token = m.group(1) != null ? m.group(1) : m.group(2);
                BigDecimal amount = parse(token);
                if (amount != null && !allowed.costDifferences.contains(amount.abs())) {
                    errors.add(new ValidationError(ValidationError.UNSUPPORTED_SAVING_CLAIM, field, token,
                            "A saving or reduction of " + token + " is not a cost difference in the evidence. Only"
                                    + " amounts from delta.*cost* items may be described as savings."));
                }
            }
        }
    }

    private static Pattern moneyPattern(String currencyCode) {
        String code = currencyCode == null || currencyCode.isBlank() ? "INR" : Pattern.quote(currencyCode);
        String symbol = "(?:" + code + "|Rs\\.?|₹|\\$|USD|EUR|€)";
        String number = "([-+]?\\d[\\d,]*(?:\\.\\d+)?)";
        // Group 1: symbol before the amount ("INR 1,200"); group 2: after it ("1,200 INR").
        return Pattern.compile("(?i)" + symbol + "\\s?" + number + "|" + number + "\\s?" + symbol);
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private static ValidationError schema(String field, String message) {
        return new ValidationError(ValidationError.SCHEMA, field, null, message);
    }

    private static Result failed(ValidationError error) {
        return new Result(null, List.of(error));
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
