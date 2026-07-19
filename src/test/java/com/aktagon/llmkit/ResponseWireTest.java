package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aktagon.llmkit.providers.generated.BatchHandle;
import com.aktagon.llmkit.providers.generated.ModelsParsers;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/*









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

    /*




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

    /*





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

    /*





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

    /*





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

    /*








*/
    private void driveModels(
            String shape, Function<byte[], ModelsParsers.ParsedModelsPage> parse) throws IOException {
        byte[] body = Files.readAllBytes(TestPaths.testdata("wire/response/v1/bodies/" + shape + ".json"));
        ModelsParsers.ParsedModelsPage page = parse.apply(body);
        ModelsParsers.ParsedModelRecord first = page.records.isEmpty() ? null : page.records.get(0);

        JsonObject firstObject = new JsonObject();
        firstObject.addProperty("contextWindow", first != null ? first.contextWindow : 0);
        firstObject.addProperty("displayName", first != null ? first.displayName : "");
        firstObject.addProperty("maxOutput", first != null ? first.maxOutput : 0);

        JsonObject content = new JsonObject();
        content.addProperty("count", page.records.size());
        content.add("first", firstObject);
        content.addProperty("firstId", first != null ? first.id : "");
        content.addProperty("kind", "models");
        content.addProperty("lastId", page.records.isEmpty() ? "" : page.records.get(page.records.size() - 1).id);
        content.addProperty("nextCursor", page.nextCursor);

        JsonObject projection = new JsonObject();
        projection.add("content", content);
        projection.add("error", JsonNull.INSTANCE);

        TestPaths.writeResponseArtifact(shape, projection);
        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/response/v1/" + shape + ".json")));
        assertEquals(golden, projection, shape + " projection differs from shared golden");
    }

    @Test
    void modelsAnthropic() throws Exception {
        driveModels("models-anthropic", ModelsParsers::parseAnthropicModelsResponse);
    }

    @Test
    void modelsOpenAI() throws Exception {
        driveModels("models-openai", ModelsParsers::parseOpenAICohortModelsResponse);
    }

    @Test
    void modelsGoogle() throws Exception {
        driveModels("models-google", ModelsParsers::parseGoogleModelsResponse);
    }

    /*










*/
    @Test
    void batchResultsAnthropic() throws Exception {
        String results = TestPaths.read(
                TestPaths.testdata("wire/response/v1/bodies/batch-results-anthropic.jsonl"));
        CapturingTransport transport = new CapturingTransport()
                .enqueue("{\"id\":\"batch_1\",\"processing_status\":\"ended\"}")
                .enqueue(results);
        BatchJob job = new BatchJob(
                new BatchHandle("batch_1", ProviderName.ANTHROPIC, false), "test-key", transport, null);

        JobStatus<List<Response>> status = job.poll();
        List<Response> responses = status.result();
        assertNotNull(responses, "expected a succeeded result, got state " + status.state());

        JsonObject first = new JsonObject();
        if (!responses.isEmpty()) {
            Response r = responses.get(0);
            JsonObject usage = new JsonObject();
            usage.addProperty("cacheRead", r.usage().cacheRead());
            usage.addProperty("cacheWrite", r.usage().cacheWrite());
            usage.addProperty("cost", r.usage().cost());
            usage.addProperty("input", r.usage().input());
            usage.addProperty("output", r.usage().output());
            usage.addProperty("reasoning", r.usage().reasoning());
            first.addProperty("finishReason", r.finishReason());
            first.addProperty("text", r.text());
            first.add("usage", usage);
        }

        JsonObject content = new JsonObject();
        content.addProperty("count", responses.size());
        content.add("first", first);
        content.addProperty("kind", "batch_results");

        JsonObject projection = new JsonObject();
        projection.add("content", content);
        projection.add("error", JsonNull.INSTANCE);

        TestPaths.writeResponseArtifact("batch-results-anthropic", projection);
        JsonElement golden = Json.parse(
                TestPaths.read(TestPaths.testdata("wire/response/v1/batch-results-anthropic.json")));
        assertEquals(golden, projection, "batch-results-anthropic projection differs from shared golden");
    }
}
