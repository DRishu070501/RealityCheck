package com.realitycheck.ai.evidence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the AI layer may say about a run, read from stored simulation results and, with a
 * baseline, the stored comparison. It is the only input a provider receives; every number a
 * provider may state is in an item's {@code value} or {@code display}.
 *
 * @param baseline  null without a baseline run
 * @param truncated items were dropped to keep the document within its size limit
 */
public record Evidence(String schemaVersion, RunFacts run, RunFacts baseline, boolean truncated,
        List<EvidenceItem> items) {

    public static final String SCHEMA_VERSION = "1";

    public Evidence {
        items = List.copyOf(items);
    }

    public Optional<EvidenceItem> item(String key) {
        return items.stream().filter(i -> i.key().equals(key)).findFirst();
    }

    public boolean has(String key) {
        return item(key).isPresent();
    }

    /** Items whose key starts with the prefix, in document order. */
    public List<EvidenceItem> withPrefix(String prefix) {
        return items.stream().filter(i -> i.key().startsWith(prefix)).toList();
    }

    /**
     * @param appliedChanges one-line summaries of the scenario changes simulated in this run
     */
    public record RunFacts(UUID runId, String scenarioName, Instant forkAt, String forkAtDisplay,
            String algorithmVersion, String terminationReason, String currencyCode, List<String> appliedChanges) {

        public RunFacts {
            appliedChanges = List.copyOf(appliedChanges);
        }
    }

    /**
     * One fact. Numeric facts have a {@code value} and preformatted {@code display} strings (for
     * example seconds also as hours); text facts (stage names, order outcomes) have {@code text}.
     */
    public record EvidenceItem(String key, String label, BigDecimal value, String text, String unit,
            List<String> display, UUID sourceRunId, UUID stageId, UUID resourceId) {

        public EvidenceItem {
            display = display == null ? List.of() : List.copyOf(display);
        }

        /** The first display string, or the text, for quoting in a sentence. */
        public String shown() {
            if (!display.isEmpty()) {
                return display.get(0);
            }
            return text != null ? text : value == null ? null : value.toPlainString();
        }
    }
}
