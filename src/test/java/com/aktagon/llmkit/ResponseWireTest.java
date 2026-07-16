package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Response-wire driver (ADR-065 direction): feed each anchored provider reply
 * ({@code codegen/testdata/wire/response/v1/bodies/<shape>.json}) through the
 * real public prompt path against the capturing transport, then normalize the
 * typed {@link Response} to the SAME projection the other five SDKs assert —
 * {@code {content, error, finishReason, usage{...}}} — dropping
 * {@code target/wire/response/<shape>/java.json} for the cross-SDK comparator
 * ({@code codegen/test_cross_sdk_response.py}). Phase 2 = the three
 * ChatCompletion shapes; media / stream / transcription shapes are driven in
 * later phases.
 */
class ResponseWireTest {

    private void drive(String shape, ProviderName provider) throws Exception {
        String body = TestPaths.read(TestPaths.testdata("wire/response/v1/bodies/" + shape + ".json"));
        CapturingTransport transport = new CapturingTransport().withResponse(200, body);
        Client client = new Client(provider, "key", transport);

        Response response = client.text().prompt("ping");

        JsonObject usage = new JsonObject();
        usage.addProperty("cacheRead", response.usage().cacheRead());
        usage.addProperty("cacheWrite", response.usage().cacheWrite());
        usage.addProperty("cost", response.usage().cost());
        usage.addProperty("input", response.usage().input());
        usage.addProperty("output", response.usage().output());
        usage.addProperty("reasoning", response.usage().reasoning());

        JsonObject projection = new JsonObject();
        projection.addProperty("content", response.text());
        projection.add("error", JsonNull.INSTANCE);
        projection.addProperty("finishReason", response.finishReason());
        projection.add("usage", usage);

        TestPaths.writeResponseArtifact(shape, projection);
        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/response/v1/" + shape + ".json")));
        assertEquals(golden, projection, shape + " projection differs from shared golden");
    }

    @Test
    void chatOpenAI() throws Exception {
        drive("chat-openai", ProviderName.OPENAI);
    }

    @Test
    void chatAnthropic() throws Exception {
        drive("chat-anthropic", ProviderName.ANTHROPIC);
    }

    @Test
    void chatGoogle() throws Exception {
        drive("chat-google", ProviderName.GOOGLE);
    }

    /**
     * Streaming variant: feed an anchored SSE frame stream
     * ({@code bodies/<shape>.sse}) through the real {@code Text.stream} path
     * and assert the assembled Response normalizes to the same projection
     * (ADR-065 B-stream).
     */
    private void driveStream(String shape, ProviderName provider) throws Exception {
        String body = TestPaths.read(TestPaths.testdata("wire/response/v1/bodies/" + shape + ".sse"));
        CapturingTransport transport = new CapturingTransport().withResponse(200, body);
        Client client = new Client(provider, "key", transport);

        Response response = client.text().stream("ping", delta -> { });

        JsonObject usage = new JsonObject();
        usage.addProperty("cacheRead", response.usage().cacheRead());
        usage.addProperty("cacheWrite", response.usage().cacheWrite());
        usage.addProperty("cost", response.usage().cost());
        usage.addProperty("input", response.usage().input());
        usage.addProperty("output", response.usage().output());
        usage.addProperty("reasoning", response.usage().reasoning());

        JsonObject projection = new JsonObject();
        projection.addProperty("content", response.text());
        projection.add("error", JsonNull.INSTANCE);
        projection.addProperty("finishReason", response.finishReason());
        projection.add("usage", usage);

        TestPaths.writeResponseArtifact(shape, projection);
        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/response/v1/" + shape + ".json")));
        assertEquals(golden, projection, shape + " projection differs from shared golden");
    }

    @Test
    void streamOpenAI() throws Exception {
        driveStream("stream-openai", ProviderName.OPENAI);
    }

    @Test
    void streamGoogle() throws Exception {
        driveStream("stream-google", ProviderName.GOOGLE);
    }
}
