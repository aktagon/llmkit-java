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
 * error classification the goldens never exercise.
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

    @Test
    void classifyErrorPrefixes() {
        assertEquals("validation_error", TelemetryRuntime.classifyError("validation: model - none"));
        assertEquals("error", TelemetryRuntime.classifyError("middleware veto: policy"));
        assertEquals("api_error", TelemetryRuntime.classifyError("openai: rate limited (429)"));
        assertEquals("", TelemetryRuntime.classifyError(""));
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
}
