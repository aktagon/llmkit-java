package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for the client-scoped telemetry seam (Phase 4g): {@code
 * addTelemetry} installs a post-phase export hook on every capability
 * builder, so a prompt emits one OTLP span carrying the call's
 * operation/provider/model/usage. The wire goldens (see {@link
 * TelemetryWireTest}) assert the pure builder; these assert the middleware
 * wiring, the honest-contract constructor rejection, the fail-open, and the
 * full structural classification table (ADR-071; the telemetry-error golden
 * exercises the api_error path end-to-end).
 */
class TelemetryTest {
    private static final String CHAT_RESPONSE =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Helsinki\"}}],"
                    + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}";

    @Test
    void addTelemetryEmitsSpanOnPrompt() {
        List<String> payloads = new ArrayList<>();
        Telemetry telemetry = new Telemetry(bytes -> payloads.add(new String(bytes, StandardCharsets.UTF_8)));
        CapturingTransport transport = new CapturingTransport().withResponse(200, CHAT_RESPONSE);

        Response response = new Client(ProviderName.OPENAI, "key", transport)
                .addTelemetry(telemetry)
                .text().model("gpt-4o").prompt("Capital of Finland?");

        assertEquals("Helsinki", response.text());

        // Exactly one span was exported (post phase only; pre is a no-op).
        assertEquals(1, payloads.size());
        JsonElement span = Json.at(Json.parse(payloads.get(0)), "resourceSpans[0].scopeSpans[0].spans[0]");
        Map<String, String> attrs = spanAttributes(span);
        assertEquals("chat", attrs.get("gen_ai.operation.name"));
        assertEquals("openai", attrs.get("gen_ai.system"));
        assertEquals("gpt-4o", attrs.get("gen_ai.request.model"));
        assertEquals("11", attrs.get("gen_ai.usage.input_tokens"));
        assertEquals("3", attrs.get("gen_ai.usage.output_tokens"));
    }

    @Test
    void telemetryIsFailOpen() {
        // An export hook that throws must never surface to the caller — the
        // prompt still returns.
        CapturingTransport transport = new CapturingTransport().withResponse(200, CHAT_RESPONSE);
        Client client = new Client(ProviderName.OPENAI, "key", transport)
                .addTelemetry(new Telemetry(bytes -> {
                    throw new RuntimeException("boom");
                }));

        Response response = client.text().model("gpt-4o").prompt("Capital of Finland?");
        assertEquals("Helsinki", response.text());
    }

    @Test
    void nullExportIsRejectedAtConstruction() {
        ValidationException thrown = assertThrows(ValidationException.class, () -> new Telemetry(null));
        assertEquals("telemetry.export", thrown.field());
    }

    /**
     * The structural classification contract (ADR-071), asserted through the
     * one erasure seam ({@code Event.toPost}): {@link ApiException} ->
     * api_error, {@link ValidationException} -> validation_error, everything
     * else -> error; success (null error) leaves err and errType null. The
     * err message stays the exception's message (byte-identical to the old
     * {@code e.getMessage()} call sites).
     */
    @Test
    void toPostStampsErrTypeStructurally() {
        Event base = Event.of(MiddlewareOp.LLM_REQUEST, "openai", "gpt-4o");

        Event api = base.toPost("", null, new ApiException("openai", 429, "rate limited"), 1);
        assertEquals("api_error", api.errType());
        assertEquals("openai: rate limited (429)", api.err());

        Event validation = base.toPost("", null, new ValidationException("model", "none"), 1);
        assertEquals("validation_error", validation.errType());
        assertEquals("validation: model - none", validation.err());

        Event transport = base.toPost("", null, new TransportException("connection reset", null), 1);
        assertEquals("error", transport.errType());

        Event success = base.toPost("", null, null, 1);
        assertEquals(null, success.err());
        assertEquals(null, success.errType());
    }

    /** Extract the single span's attributes as a flat {@code [key: stringValueOrIntValue]}. */
    private static Map<String, String> spanAttributes(JsonElement span) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonElement attrs = span.getAsJsonObject().get("attributes");
        for (JsonElement attr : attrs.getAsJsonArray()) {
            String key = attr.getAsJsonObject().get("key").getAsString();
            JsonObject value = attr.getAsJsonObject().getAsJsonObject("value");
            if (value.has("stringValue")) {
                out.put(key, value.get("stringValue").getAsString());
            } else if (value.has("intValue")) {
                out.put(key, value.get("intValue").getAsString());
            }
        }
        return out;
    }

    @Test
    void httpExportPostsPayloadWithHeadersToTracesPath() throws Exception {
        List<String> requestLines = new ArrayList<>();
        Map<String, String> received = new LinkedHashMap<>();
        byte[][] receivedBody = new byte[1][];
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            received.put("content-type", exchange.getRequestHeaders().getFirst("Content-Type"));
            received.put("x-otlp-key", exchange.getRequestHeaders().getFirst("x-otlp-key"));
            receivedBody[0] = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] payload = "{\"resourceSpans\":[]}".getBytes(StandardCharsets.UTF_8);

            Telemetry.httpExport(endpoint, Map.of("x-otlp-key", "collector-secret"))
                    .accept(payload);

            assertEquals(
                    List.of("POST " + com.aktagon.llmkit.providers.generated.TelemetryGen.TRACES_PATH),
                    requestLines);
            assertEquals("application/json", received.get("content-type"));
            assertEquals("collector-secret", received.get("x-otlp-key"));
            assertEquals(new String(payload, StandardCharsets.UTF_8),
                    new String(receivedBody[0], StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }
}
