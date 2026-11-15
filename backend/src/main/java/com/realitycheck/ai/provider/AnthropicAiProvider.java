package com.realitycheck.ai.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.ai.provider.AiProviderException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Claude, through the official Anthropic Java SDK. The only class that knows the vendor's
 * request and response format. The SDK retries timeouts, 429 and 5xx with backoff up to the
 * configured limit; the response is constrained to the insight JSON schema, and is still
 * validated by the caller.
 */
public final class AnthropicAiProvider implements AiProvider {

    public static final String NAME = "anthropic";

    private static final JsonOutputFormat.Schema SCHEMA = loadSchema();

    private final AnthropicClient client;
    private final String model;

    public AnthropicAiProvider(String apiKey, String model, String baseUrl, Duration timeout, int maxRetries) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(timeout)
                .maxRetries(maxRetries);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
        this.model = model;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean languageModel() {
        return true;
    }

    @Override
    public AiResponse complete(AiRequest request) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxOutputTokens())
                .system(request.systemPrompt())
                .addUserMessage(request.userMessage())
                .outputConfig(OutputConfig.builder()
                        .format(JsonOutputFormat.builder().schema(SCHEMA).build())
                        // Explaining stored figures needs little reasoning; low effort keeps thinking
                        // short, which cuts latency and output cost and avoids hitting the token limit.
                        .effort(OutputConfig.Effort.LOW)
                        .build())
                .build();
        Message message;
        try {
            message = client.messages().create(params);
        } catch (AnthropicServiceException ex) {
            // The status code only: vendor error bodies are not echoed.
            throw new AiProviderException(Reason.PROVIDER_ERROR, "Claude returned HTTP " + ex.statusCode(), ex);
        } catch (AnthropicIoException ex) {
            throw isTimeout(ex)
                    ? new AiProviderException(Reason.TIMEOUT, "Claude did not answer in time", ex)
                    : new AiProviderException(Reason.PROVIDER_ERROR, "Claude could not be reached", ex);
        } catch (AnthropicException ex) {
            throw new AiProviderException(Reason.PROVIDER_ERROR, "Claude request failed: "
                    + ex.getClass().getSimpleName(), ex);
        }
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new AiProviderException(Reason.PROVIDER_ERROR, "Claude declined to answer");
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(block -> block.text())
                .collect(Collectors.joining());
        boolean truncated = message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent();
        return new AiResponse(text, message.model().asString(),
                Math.toIntExact(message.usage().inputTokens()), Math.toIntExact(message.usage().outputTokens()),
                truncated);
    }

    private static boolean isTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private static JsonOutputFormat.Schema loadSchema() {
        try (InputStream in = AnthropicAiProvider.class.getResourceAsStream("/ai/insight-schema.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing /ai/insight-schema.json");
            }
            JsonNode root = new ObjectMapper().readTree(in);
            JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
            for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                schema.putAdditionalProperty(field.getKey(), JsonValue.fromJsonNode(field.getValue()));
            }
            return schema.build();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
