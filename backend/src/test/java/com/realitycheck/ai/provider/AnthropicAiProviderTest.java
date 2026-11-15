package com.realitycheck.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.ai.EvidenceFixtures;
import com.realitycheck.ai.domain.InsightType;
import com.realitycheck.ai.evidence.EvidenceBuilder;
import com.realitycheck.ai.provider.AiProviderException.Reason;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a local stub of the Messages API: no network, no real key. */
class AnthropicAiProviderTest {

    private static final String KEY = "sk-ant-test-not-a-real-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Handler handler;

    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            calls.incrementAndGet();
            // The SDK sends the key as x-api-key or as a bearer token, depending on its kind.
            var headers = exchange.getRequestHeaders();
            keys.add(headers.containsKey("x-api-key") ? headers.getFirst("x-api-key")
                    : String.valueOf(headers.getFirst("Authorization")).replaceFirst("^Bearer ", ""));
            bodies.add(JSON.readTree(exchange.getRequestBody()));
            handler.handle(exchange);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicAiProvider provider(Duration timeout, int retries) {
        return new AnthropicAiProvider(KEY, "claude-haiku-5-5",
                "http://127.0.0.1:" + server.getAddress().getPort(), timeout, retries);
    }

    private static AiRequest request() {
        return new AiRequest(InsightType.BOTTLENECK, "SYSTEM RULES", "USER EVIDENCE",
                EvidenceBuilder.build(EvidenceFixtures.run(), null, null), null, 1234);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String message(String text, String stopReason) throws IOException {
        return JSON.writeValueAsString(JSON.createObjectNode()
                .put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", "claude-haiku-5-5")
                .put("stop_reason", stopReason)
                .set("content", JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text").put("text", text))))
                .replaceFirst("}$", ",\"usage\":{\"input_tokens\":321,\"output_tokens\":54}}");
    }

    @Test
    void sendsTheModelPromptsTokenLimitAndSchemaAndReadsTheReply() throws IOException {
        String text = "{\"summary\":\"ok\"}";
        String body = message(text, "end_turn");
        handler = exchange -> reply(exchange, 200, body);

        AiResponse response = provider(Duration.ofSeconds(5), 0).complete(request());

        assertThat(response.rawJson()).isEqualTo(text);
        assertThat(response.model()).isEqualTo("claude-haiku-5-5");
        assertThat(response.inputTokens()).isEqualTo(321);
        assertThat(response.outputTokens()).isEqualTo(54);
        assertThat(response.truncated()).isFalse();
        assertThat(keys).containsExactly(KEY);
        JsonNode sent = bodies.get(0);
        assertThat(sent.path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(1234);
        assertThat(sent.path("system").toString()).contains("SYSTEM RULES");
        assertThat(sent.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(sent.path("messages").toString()).contains("USER EVIDENCE");
        JsonNode format = sent.path("output_config").path("format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("schema").path("required").toString()).contains("summary", "recommendations");
        assertThat(sent.path("output_config").path("effort").asText()).isEqualTo("low");
    }

    @Test
    void aReplyCutOffAtTheTokenLimitIsMarkedTruncated() throws IOException {
        String body = message("{\"summary\":\"cu", "max_tokens");
        handler = exchange -> reply(exchange, 200, body);

        assertThat(provider(Duration.ofSeconds(5), 0).complete(request()).truncated()).isTrue();
    }

    @Test
    void serverErrorsAreRetriedUpToTheLimitThenReportedWithoutTheKey() {
        handler = exchange -> reply(exchange, 500,
                "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom " + KEY + "\"}}");

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5), 2).complete(request()))
                .isInstanceOfSatisfying(AiProviderException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(Reason.PROVIDER_ERROR);
                    assertThat(ex.getMessage()).isEqualTo("Claude returned HTTP 500").doesNotContain(KEY);
                });
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void rateLimitsAreRetriedToo() {
        handler = exchange -> reply(exchange, 429,
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5), 1).complete(request()))
                .isInstanceOf(AiProviderException.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aSlowServerIsATimeout() {
        handler = exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        };

        assertThatThrownBy(() -> provider(Duration.ofMillis(300), 0).complete(request()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.TIMEOUT));
    }

    @Test
    void aRefusalIsAProviderError() throws IOException {
        String body = message("", "refusal");
        handler = exchange -> reply(exchange, 200, body);

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5), 0).complete(request()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.PROVIDER_ERROR));
    }
}
