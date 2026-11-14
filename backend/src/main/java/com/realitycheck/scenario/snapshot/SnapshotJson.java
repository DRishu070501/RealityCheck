package com.realitycheck.scenario.snapshot;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The one way snapshots are written, read and hashed. Hashing serialises to canonical JSON
 * (object keys sorted at every level, decimals written plainly with their scale, instants in
 * ISO-8601 UTC), so the same snapshot always produces the same SHA-256.
 */
public final class SnapshotJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            // Decimals keep their scale in trees too: 6.0000 stays 6.0000.
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private static final JsonNodeFactory EXACT = JsonNodeFactory.withExactBigDecimals(true);

    private SnapshotJson() {
    }

    public static String write(Snapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Snapshot could not be serialised", ex);
        }
    }

    public static Snapshot read(String json) {
        try {
            return MAPPER.readValue(json, Snapshot.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored snapshot could not be read", ex);
        }
    }

    /** SHA-256 of the canonical JSON of any value, as 64 lowercase hex characters. */
    public static String hash(Object value) {
        JsonNode canonical = sorted(MAPPER.valueToTree(value));
        try {
            byte[] bytes = MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Snapshot could not be hashed", ex);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            object.fields().forEachRemaining(fields::add);
            fields.sort(Map.Entry.comparingByKey());
            ObjectNode result = EXACT.objectNode();
            for (Map.Entry<String, JsonNode> field : fields) {
                result.set(field.getKey(), sorted(field.getValue()));
            }
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = EXACT.arrayNode();
            array.forEach(element -> result.add(sorted(element)));
            return result;
        }
        return node;
    }
}
