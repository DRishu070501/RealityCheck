package com.realitycheck.ai.evidence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.realitycheck.ai.evidence.Evidence.EvidenceItem;
import com.realitycheck.ai.evidence.Evidence.RunFacts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The evidence document as canonical JSON (for its hash) and as the compact form sent to a model. */
public final class EvidenceJson {

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private EvidenceJson() {
    }

    /** SHA-256 of the canonical JSON, lower-case hex. Equal evidence always gives an equal hash. */
    public static String hash(Evidence evidence) {
        try {
            byte[] bytes = CANONICAL.writeValueAsString(evidence).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Evidence could not be hashed", ex);
        }
    }

    /**
     * What a model reads: facts about each run and every item's key, label, value or text, unit and
     * display strings. Internal identifiers are left out; the keys identify items.
     */
    public static String forPrompt(Evidence evidence) {
        ObjectNode root = CANONICAL.createObjectNode();
        root.put("schemaVersion", evidence.schemaVersion());
        root.set("run", facts(evidence.run()));
        if (evidence.baseline() != null) {
            root.set("baseline", facts(evidence.baseline()));
        }
        root.put("truncated", evidence.truncated());
        ArrayNode items = root.putArray("items");
        for (EvidenceItem item : evidence.items()) {
            ObjectNode node = items.addObject();
            node.put("key", item.key());
            node.put("label", item.label());
            if (item.value() != null) {
                node.put("value", item.value().stripTrailingZeros().toPlainString());
                node.put("unit", item.unit());
                ArrayNode display = node.putArray("display");
                item.display().forEach(display::add);
            } else {
                node.put("text", item.text());
            }
        }
        try {
            return CANONICAL.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Evidence could not be written", ex);
        }
    }

    private static ObjectNode facts(RunFacts facts) {
        ObjectNode node = CANONICAL.createObjectNode();
        node.put("scenarioName", facts.scenarioName());
        node.put("simulationStartsAt", facts.forkAtDisplay());
        node.put("algorithmVersion", facts.algorithmVersion());
        node.put("terminationReason", facts.terminationReason());
        node.put("currencyCode", facts.currencyCode());
        ArrayNode changes = node.putArray("appliedChanges");
        facts.appliedChanges().forEach(changes::add);
        return node;
    }
}
