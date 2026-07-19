package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/*







*/
class TelemetryWireTest {
    private void assertGolden(String fixture, String payload) throws IOException {
        TestPaths.writeTelemetryArtifact(fixture, payload);
        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/telemetry/v1/" + fixture + ".json")));
        assertEquals(golden, Json.parse(payload), fixture + " OTLP payload differs from shared golden");
    }

    @Test
    void telemetrySuccess() throws IOException {
        String payload = TelemetryRuntime.buildOTLPTraces(
                "chat", "openai", "gpt-4o",
                10, 20, "",
                "5b8efff798038103d269b633813fc60c", "eee19b7ec3c1b174",
                "1700000000000000000", "1700000001000000000");
        assertGolden("telemetry-success", payload);
    }

    @Test
    void telemetryRejection() throws IOException {
        String payload = TelemetryRuntime.buildOTLPTraces(
                "chat", "openai", "gpt-4o",
                0, 0, "rate_limit_exceeded",
                "5b8efff798038103d269b633813fc60c", "eee19b7ec3c1b174",
                "1700000000000000000", "1700000001000000000");
        assertGolden("telemetry-rejection", payload);
    }

    /*




*/
    @Test
    void telemetryError() throws IOException {
        Event event = Event.of(MiddlewareOp.LLM_REQUEST, "openai", "gpt-4o")
                .toPost("", null, new ApiException("openai", 429, "rate limited"), 1000);
        assertEquals("api_error", event.errType(), "toPost must stamp errType from the typed error");
        String payload = TelemetryRuntime.buildPayloadAt(
                event,
                "5b8efff798038103d269b633813fc60c", "eee19b7ec3c1b174",
                "1700000000000000000", "1700000001000000000");
        assertGolden("telemetry-error", payload);
    }
}
