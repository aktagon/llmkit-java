package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    /**
     * Image variant: feed an anchored image-generation reply through the real
     * {@code Image.generate} path and assert the decoded {@code ImageResponse}
     * projects to the media discriminant {@code {kind, mimeType, byteLen,
     * count}} (RWR-004) — the same body must decode to the same images across
     * all six SDKs (BUG-024).
     */
    private void driveImage(String shape, ProviderName provider, String model) throws Exception {
        String body = TestPaths.read(TestPaths.testdata("wire/response/v1/bodies/" + shape + ".json"));
        CapturingTransport transport = new CapturingTransport().withResponse(200, body);
        Client client = new Client(provider, "key", transport);

        var response = client.image().model(model).generate("a cat");
        var first = response.images().isEmpty() ? null : response.images().get(0);

        JsonObject content = new JsonObject();
        content.addProperty("byteLen", first != null ? first.bytes().length : 0);
        content.addProperty("count", response.images().size());
        content.addProperty("kind", "image");
        content.addProperty("mimeType", first != null ? first.mimeType() : "");

        JsonObject usage = new JsonObject();
        usage.addProperty("cacheRead", response.usage().cacheRead());
        usage.addProperty("cacheWrite", response.usage().cacheWrite());
        usage.addProperty("cost", response.usage().cost());
        usage.addProperty("input", response.usage().input());
        usage.addProperty("output", response.usage().output());
        usage.addProperty("reasoning", response.usage().reasoning());

        JsonObject projection = new JsonObject();
        projection.add("content", content);
        projection.add("error", JsonNull.INSTANCE);
        projection.addProperty("finishReason", response.finishReason());
        projection.add("usage", usage);

        TestPaths.writeResponseArtifact(shape, projection);
        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/response/v1/" + shape + ".json")));
        assertEquals(golden, projection, shape + " projection differs from shared golden");
    }

    @Test
    void imageGoogle() throws Exception {
        driveImage("image-google", ProviderName.GOOGLE, "gemini-3.1-flash-image-preview");
    }

    @Test
    void imageOpenAI() throws Exception {
        driveImage("image-openai", ProviderName.OPENAI, "gpt-image-1");
    }

    @Test
    void imageVertex() throws Exception {
        driveImage("image-vertex", ProviderName.VERTEX, "imagen-3.0-generate-002");
    }

    /**
     * Speech variant: feed an anchored TTS reply (an Inworld base64-envelope
     * body) through the real {@code Speech.generate} path and assert the
     * decoded {@code SpeechResponse} projects to the media discriminant
     * {@code {kind, mimeType, byteLen}} (RWR-004) — the same body must
     * decode to the same audio across all six SDKs.
     */
    private void driveSpeech(String shape, ProviderName provider, String model, String voice) throws Exception {
        String body = TestPaths.read(TestPaths.testdata("wire/response/v1/bodies/" + shape + ".json"));
        CapturingTransport transport = new CapturingTransport().withResponse(200, body);
        Client client = new Client(provider, "key", transport);

        var response = client.speech().model(model).voice(voice).generate("ping");

        JsonObject content = new JsonObject();
        content.addProperty("byteLen", response.audio().bytes().length);
        content.addProperty("kind", "speech");
        content.addProperty("mimeType", response.audio().mimeType());

        JsonObject usage = new JsonObject();
        usage.addProperty("cacheRead", response.usage().cacheRead());
        usage.addProperty("cacheWrite", response.usage().cacheWrite());
        usage.addProperty("cost", response.usage().cost());
        usage.addProperty("input", response.usage().input());
        usage.addProperty("output", response.usage().output());
        usage.addProperty("reasoning", response.usage().reasoning());

        JsonObject projection = new JsonObject();
        projection.add("content", content);
        projection.add("error", JsonNull.INSTANCE);
        projection.addProperty("finishReason", response.finishReason());
        projection.add("usage", usage);

        TestPaths.writeResponseArtifact(shape, projection);
        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/response/v1/" + shape + ".json")));
        assertEquals(golden, projection, shape + " projection differs from shared golden");
    }

    @Test
    void speechInworld() throws Exception {
        driveSpeech("speech-inworld", ProviderName.INWORLD, "inworld-tts-2", "Dennis");
    }

    /**
     * Transcription variant: feed an anchored OpenAI verbose_json reply through
     * the real synchronous {@code transcribe} path and assert the decoded
     * {@code TranscriptionResponse} projects to the transcript discriminant
     * {@code {kind, segments, text}} (ADR-065 / ADR-048) — the same body must
     * decode to the same transcript across all six SDKs.
     */
    private void driveTranscription(String shape, ProviderName provider, String model) throws Exception {
        String body = TestPaths.read(TestPaths.testdata("wire/response/v1/bodies/" + shape + ".json"));
        CapturingTransport transport = new CapturingTransport().withResponse(200, body);
        Client client = new Client(provider, "key", transport);

        var response = client.transcription()
                .model(model)
                .transcribe(List.of(Part.audioBytes("audio/mpeg", "fake-audio".getBytes(StandardCharsets.UTF_8))));

        JsonObject content = new JsonObject();
        content.addProperty("kind", "transcript");
        content.addProperty("segments", response.segments().size());
        content.addProperty("text", response.text());

        JsonObject usage = new JsonObject();
        usage.addProperty("cacheRead", response.usage().cacheRead());
        usage.addProperty("cacheWrite", response.usage().cacheWrite());
        usage.addProperty("cost", response.usage().cost());
        usage.addProperty("input", response.usage().input());
        usage.addProperty("output", response.usage().output());
        usage.addProperty("reasoning", response.usage().reasoning());

        JsonObject projection = new JsonObject();
        projection.add("content", content);
        projection.add("error", JsonNull.INSTANCE);
        projection.addProperty("finishReason", "");
        projection.add("usage", usage);

        TestPaths.writeResponseArtifact(shape, projection);
        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/response/v1/" + shape + ".json")));
        assertEquals(golden, projection, shape + " projection differs from shared golden");
    }

    @Test
    void transcriptionOpenAI() throws Exception {
        driveTranscription("transcription-openai", ProviderName.OPENAI, "whisper-1");
    }
}
