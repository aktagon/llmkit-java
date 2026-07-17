package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Telemetry-wire driver (ADR-054 TEL-011): call the PURE OTLP builder with the
 * SAME fixed inputs the other five SDKs feed and assert the payload is
 * value-equal to the shared golden at
 * {@code codegen/testdata/wire/telemetry/v1/}. Each test also drops {@code
 * target/wire/telemetry/<fixture>/java.json} so the cross-SDK comparator
 * ({@code codegen/test_cross_sdk_telemetry_wire.py}) can enroll Java. Span
 * identity + timing are injected fixed so the payload is byte-stable.
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
}
