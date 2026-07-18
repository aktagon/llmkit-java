package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Request-wire driver (ADR-028 direction): build each ChatCompletion request
 * through the full SDK stack, capture the outbound bytes via the injected
 * {@link CapturingTransport}, and assert the body is value-equal to the SAME
 * shared golden at {@code codegen/testdata/wire/request/v1/<fixture>.json} that
 * the other five SDKs assert. Each test also drops
 * {@code target/wire/request/<fixture>/java.json} so the cross-SDK comparator
 * ({@code codegen/test_cross_sdk_request_wire.py}) enrolls Java. Inputs are the
 * SAME canonical values the other drivers feed (single-sourced in
 * {@code ontology/wire-fixtures.ttl}; hand-mirrored here — test drivers are
 * never generated). Phase 2 = ChatCompletion; media Parts / tools / batch /
 * SigV4 / media capabilities are driven in later phases.
 */
class RequestWireTest {
    private CapturingTransport transport;

    private Client client(ProviderName provider) {
        transport = new CapturingTransport().withResponse(200, "{}");
        return new Client(provider, "key", transport);
    }

    private void assertGolden(String fixture) throws IOException {
        assertGolden(fixture, Json.parse(transport.capturedBody));
    }

    private void assertGolden(String fixture, JsonElement body) throws IOException {
        TestPaths.writeRequestArtifact(fixture, body);
        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/request/v1/" + fixture + ".json")));
        assertEquals(golden, body, fixture + " body differs from shared golden");
    }

    // --- Options (one per model family; the double-serialization surface) ---

    @Test
    void optionsOpenAIGPT4O() throws Exception {
        client(ProviderName.OPENAI).text()
                .model("gpt-4o").maxTokens(256).temperature(0.7).topP(0.9)
                .stopSequences(List.of("END_OF_LIST")).seed(42).frequencyPenalty(0.25).presencePenalty(0.15)
                .prompt("List three primary colors, then write END_OF_LIST.");
        assertGolden("options-openai-gpt4o");
    }

    @Test
    void optionsOpenAIGPT5() throws Exception {
        client(ProviderName.OPENAI).text()
                .model("gpt-5").maxTokens(1024).reasoningEffort("low").seed(42)
                .prompt("Summarize the plot of Hamlet in two sentences.");
        assertGolden("options-openai-gpt5");
    }

    @Test
    void optionsOpenAIOSeries() throws Exception {
        client(ProviderName.OPENAI).text()
                .model("o4-mini").maxTokens(1024).reasoningEffort("medium").seed(7)
                .prompt("What is the capital of Finland?");
        assertGolden("options-openai-o-series");
    }

    @Test
    void optionsAnthropic() throws Exception {
        client(ProviderName.ANTHROPIC).text()
                .model("claude-sonnet-4-6").maxTokens(2048).thinkingBudget(1024)
                .stopSequences(List.of("END_OF_ANSWER"))
                .prompt("Explain in one sentence why the sky appears blue at noon, then write END_OF_ANSWER.");
        assertGolden("options-anthropic");
    }

    @Test
    void optionsAnthropicAdaptive() throws Exception {
        client(ProviderName.ANTHROPIC).text()
                .model("claude-opus-4-7").maxTokens(2048).reasoningEffort("medium")
                .stopSequences(List.of("END_OF_ANSWER"))
                .prompt("State the boiling point of water at sea level in Celsius, then write END_OF_ANSWER.");
        assertGolden("options-anthropic-adaptive");
    }

    @Test
    void optionsAnthropicPlain() throws Exception {
        client(ProviderName.ANTHROPIC).text()
                .model("claude-sonnet-4-6").maxTokens(1024).temperature(0.7).topK(40)
                .stopSequences(List.of("END_OF_ANSWER"))
                .prompt("Name the longest river in Finland, then write END_OF_ANSWER.");
        assertGolden("options-anthropic-plain");
    }

    @Test
    void optionsGoogle() throws Exception {
        client(ProviderName.GOOGLE).text()
                .model("gemini-3.5-flash").maxTokens(1024).temperature(0.7).topP(0.9).topK(40)
                .stopSequences(List.of("END_OF_ANSWER")).seed(7).reasoningEffort("low")
                .safetySettings(List.of(
                        new SafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_ONLY_HIGH")))
                .prompt("Name the two largest moons of Jupiter, then write END_OF_ANSWER.");
        assertGolden("options-google");
    }

    @Test
    void optionsGoogleGemini25() throws Exception {
        client(ProviderName.GOOGLE).text()
                .model("gemini-2.5-flash").maxTokens(1024).temperature(0.5).thinkingBudget(512)
                .prompt("How many planets orbit the Sun? Answer with a number.");
        assertGolden("options-google-gemini25");
    }

    // --- OpenAI-compat fleet ---

    @Test
    void workersAI() throws Exception {
        client(ProviderName.WORKERSAI).baseUrl("https://mock.local/v1").text()
                .model("@cf/meta/llama-3.1-8b-instruct").maxTokens(512).temperature(0.7).topP(0.9)
                .prompt("List three primary colors as a comma-separated list.");
        assertGolden("workersai");
    }

    // --- Responses protocol (ADR-055) ---

    @Test
    void responsesOpenAI() throws Exception {
        client(ProviderName.OPENAI).text()
                .protocol("responses").model("gpt-4o-mini").maxTokens(256)
                .prompt("Name the capital of Finland in one word.");
        assertGolden("responses-openai");
    }

    // --- Structured output (schema normalization) ---

    private static final String SCHEMA_FLAT =
            "{\"type\":\"object\",\"properties\":{\"color\":{\"type\":\"string\"}},"
                    + "\"additionalProperties\":false}";
    private static final String SCHEMA_NESTED =
            "{\"type\":\"object\",\"properties\":{\"residence\":{\"type\":\"object\",\"properties\":"
                    + "{\"addresses\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":"
                    + "{\"city\":{\"type\":\"string\"}},\"additionalProperties\":false}}},"
                    + "\"additionalProperties\":false}},\"additionalProperties\":false}";
    private static final String PROMPT_FLAT = "What color is a clear daytime sky?";
    private static final String PROMPT_NESTED =
            "Name a coastal city in Finland where a harbor pilot might reside. Reply as structured data.";

    @Test
    void structuredOutputOpenAI() throws Exception {
        client(ProviderName.OPENAI).text()
                .model("gpt-4o-2024-08-06").schema(SCHEMA_FLAT).prompt(PROMPT_FLAT);
        assertGolden("structured-output-openai");
    }

    @Test
    void structuredOutputGoogle() throws Exception {
        client(ProviderName.GOOGLE).text().schema(SCHEMA_FLAT).prompt(PROMPT_FLAT);
        assertGolden("structured-output-google");
    }

    @Test
    void structuredOutputAnthropic() throws Exception {
        client(ProviderName.ANTHROPIC).text()
                .model("claude-sonnet-4-6").schema(SCHEMA_FLAT).prompt(PROMPT_FLAT);
        assertGolden("structured-output-anthropic");
        // Load-bearing headers: without the structured-output beta Anthropic
        // 400s on output_format. Golden-locked across all SDKs via the companion
        // structured-output-anthropic.headers.json.
        assertEquals(
                "structured-outputs-2025-11-13", transport.capturedHeaders.get("anthropic-beta"));
        TestPaths.writeRequestHeaders("structured-output-anthropic", transport.capturedHeaders);
    }

    @Test
    void structuredOutputNestedOpenAI() throws Exception {
        client(ProviderName.OPENAI).text()
                .model("gpt-4o-2024-08-06").schema(SCHEMA_NESTED).prompt(PROMPT_NESTED);
        assertGolden("structured-output-nested-openai");
    }

    @Test
    void structuredOutputNestedGoogle() throws Exception {
        client(ProviderName.GOOGLE).text().schema(SCHEMA_NESTED).prompt(PROMPT_NESTED);
        assertGolden("structured-output-nested-google");
    }

    @Test
    void structuredOutputNestedAnthropic() throws Exception {
        client(ProviderName.ANTHROPIC).text()
                .model("claude-sonnet-4-6").schema(SCHEMA_NESTED).prompt(PROMPT_NESTED);
        assertGolden("structured-output-nested-anthropic");
    }

    // --- Streaming (BUG-028: stream_options.include_usage on the body) ---

    @Test
    void streamOpenAI() throws Exception {
        client(ProviderName.OPENAI).text().model("gpt-4o-mini").stream("Say hello.", delta -> { });
        assertGolden("stream-openai");
    }

    // --- Agent tool definitions (per wire shape) ---

    private static final String TOOL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"additionalProperties\":false}";
    private static final String TOOL_PROMPT = "What is the weather in Helsinki right now?";

    private Tool weatherTool() {
        return new Tool(
                "get_weather",
                "Get the current weather for a city.",
                Json.parse(TOOL_SCHEMA),
                args -> "");
    }

    @Test
    void toolDefOpenAI() throws Exception {
        client(ProviderName.OPENAI).agent().addTool(weatherTool()).prompt(TOOL_PROMPT);
        assertGolden("tooldef-openai");
    }

    @Test
    void toolDefAnthropic() throws Exception {
        client(ProviderName.ANTHROPIC).agent().addTool(weatherTool()).prompt(TOOL_PROMPT);
        assertGolden("tooldef-anthropic");
    }

    @Test
    void toolDefGoogle() throws Exception {
        client(ProviderName.GOOGLE).agent().addTool(weatherTool()).prompt(TOOL_PROMPT);
        assertGolden("tooldef-google");
    }

    @Test
    void toolDefBedrock() throws Exception {
        client(ProviderName.BEDROCK).agent().addTool(weatherTool()).prompt(TOOL_PROMPT);
        assertGolden("tooldef-bedrock");
    }

    // --- Media Parts on the text path (ADR-060: vision image + file refs) ---

    // The shared 1x1 PNG the other SDKs feed, decoded to bytes for .image(...).
    private static final String IMAGE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGM4YWQEAALyAS2saifrAAAAAElFTkSuQmCC";

    private static byte[] imageBytes() {
        return java.util.Base64.getDecoder().decode(IMAGE_BASE64);
    }

    @Test
    void openAITextImage() throws Exception {
        client(ProviderName.OPENAI).text()
                .model("gpt-4o").image("image/png", imageBytes())
                .prompt("Describe the attached image in one sentence.");
        assertGolden("openai-text-image");
    }

    @Test
    void anthropicTextImage() throws Exception {
        client(ProviderName.ANTHROPIC).text()
                .model("claude-opus-4-8").image("image/png", imageBytes())
                .prompt("Describe the attached image in one sentence.");
        assertGolden("anthropic-text-image");
    }

    @Test
    void googleTextImage() throws Exception {
        client(ProviderName.GOOGLE).text()
                .model("gemini-2.5-flash").image("image/png", imageBytes())
                .prompt("Describe the attached image in one sentence.");
        assertGolden("google-text-image");
    }

    @Test
    void bedrockTextImage() throws Exception {
        client(ProviderName.BEDROCK).text()
                .model("anthropic.claude-sonnet-4-20250514-v1:0").image("image/png", imageBytes())
                .prompt("Describe the attached image in one sentence.");
        assertGolden("bedrock-text-image");
    }

    @Test
    void openAITextDocument() throws Exception {
        client(ProviderName.OPENAI).text()
                .model("gpt-4o").file("file-9aXr2bQ7m1Tn")
                .prompt("Summarize the attached document in three sentences.");
        assertGolden("openai-text-document");
    }

    @Test
    void anthropicTextDocument() throws Exception {
        client(ProviderName.ANTHROPIC).text()
                .model("claude-opus-4-8").file("file_011CMZq8h5VnVe8jL3qK7p2R")
                .prompt("Summarize the attached document in three sentences.");
        assertGolden("anthropic-text-document");
        // BUG-017: a file-referencing Anthropic request must carry the
        // files-api beta; golden-locked across all SDKs via
        // anthropic-text-document.headers.json.
        assertEquals("files-api-2025-04-14", transport.capturedHeaders.get("anthropic-beta"));
        TestPaths.writeRequestHeaders("anthropic-text-document", transport.capturedHeaders);
    }

    @Test
    void anthropicSchemaDocument() throws Exception {
        String schema =
                "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},"
                        + "\"additionalProperties\":false}";
        client(ProviderName.ANTHROPIC).text()
                .model("claude-opus-4-8").schema(schema).file("file_011CMZq8h5VnVe8jL3qK7p2R")
                .prompt("Summarize the attached document as structured data.");
        assertGolden("anthropic-schema-document");
        // BUG-017 compose path: the structured-output beta and the files-api
        // beta compose into one comma-separated anthropic-beta, deduped.
        assertEquals(
                "structured-outputs-2025-11-13,files-api-2025-04-14",
                transport.capturedHeaders.get("anthropic-beta"));
        TestPaths.writeRequestHeaders("anthropic-schema-document", transport.capturedHeaders);
    }

    @Test
    void batchMultimodalAnthropic() throws Exception {
        Client c = client(ProviderName.ANTHROPIC);
        transport.withResponse(200, "{\"id\":\"batch_1\"}");
        c.text()
                .model("claude-sonnet-4-6")
                .file("file_011CMZq8h5VnVe8jL3qK7p2R")
                .image("image/png", imageBytes())
                .batch("Summarize the attached document and describe the image in one sentence.");
        assertGolden("batch-multimodal-anthropic");
        // The batch CREATE request lifts the per-item files-api beta (BUG-017).
        assertEquals("files-api-2025-04-14", transport.capturedHeaders.get("anthropic-beta"));
        TestPaths.writeRequestHeaders("batch-multimodal-anthropic", transport.capturedHeaders);
    }

    // --- Caching (Anthropic explicit cache_control on the system prefix) ---

    private static final String CACHING_SYSTEM = "a long stable system prefix";
    private static final String CACHING_PROMPT = "hi";

    @Test
    void cachingTextAnthropic() throws Exception {
        client(ProviderName.ANTHROPIC).text()
                .system(CACHING_SYSTEM).caching().prompt(CACHING_PROMPT);
        assertGolden("caching-text-anthropic");
    }

    @Test
    void cachingAgentAnthropic() throws Exception {
        // cacheTtl is a no-op under explicit caching (Anthropic ignores it; it
        // only feeds resource caching) — exercised here alongside .caching() so
        // both Agent setters are covered without perturbing the golden body.
        client(ProviderName.ANTHROPIC).agent()
                .system(CACHING_SYSTEM).caching().cacheTtl(600).prompt(CACHING_PROMPT);
        assertGolden("caching-agent-anthropic");
    }

    @Test
    void cachingBatchAnthropic() throws Exception {
        Client c = client(ProviderName.ANTHROPIC);
        // The batch CREATE response must carry an id so submit does not throw;
        // the assertion is on the captured CREATE request body, not the reply.
        transport.withResponse(200, "{\"id\":\"batch_1\"}");
        c.text().system(CACHING_SYSTEM).caching().batch(CACHING_PROMPT);
        assertGolden("caching-batch-anthropic");
    }

    // --- Image generation (JSON bodies only; multipart edits are a WIRE-008
    // documented exclusion). Inputs mirror the WIRE_IMAGE_* wire_inputs constants.

    @Test
    void imageGenGoogleFlash() throws Exception {
        client(ProviderName.GOOGLE).image()
                .model("gemini-3.1-flash-image-preview").aspectRatio("16:9").imageSize("2K")
                .generate("A lighthouse on a rocky coastline at dusk");
        assertGolden("image-gen-google-flash");
    }

    @Test
    void imageGenGooglePro() throws Exception {
        client(ProviderName.GOOGLE).image()
                .model("gemini-3-pro-image-preview").aspectRatio("4:3").imageSize("1K").includeText()
                .generate("A watercolor map of the Baltic Sea");
        assertGolden("image-gen-google-pro");
    }

    @Test
    void imageGenOpenAI() throws Exception {
        client(ProviderName.OPENAI).image()
                .model("gpt-image-2").imageSize("1024x1024").quality("low")
                .outputFormat("png").background("opaque").count(1)
                .generate("A minimalist line drawing of a sailboat");
        assertGolden("image-gen-openai");
    }

    @Test
    void imageGenRecraft() throws Exception {
        client(ProviderName.RECRAFT).image()
                .model("recraftv3").imageSize("1024x1024").count(1)
                .generate("A minimalist line drawing of a sailboat");
        assertGolden("image-gen-recraft");
    }

    @Test
    void imageEditGoogleFlash() throws Exception {
        client(ProviderName.GOOGLE).image()
                .model("gemini-3.1-flash-image-preview").image("image/png", imageBytes())
                .generate("Recolor the square to deep blue");
        assertGolden("image-edit-google-flash");
    }

    // --- Speech generation (TTS). Inputs mirror the WIRE_SPEECH_* wire_inputs
    // constants; the two shapes are the flat-JSON Inworld body (Basic auth)
    // and the flat-JSON OpenAI body.

    @Test
    void speechInworld() throws Exception {
        // The base64Envelope parser rejects a bodyless 2xx since HANDOFF-036
        // A5, so this driver (like the other SDKs' wire drivers) feeds a valid
        // envelope; the assertion is on the captured request bytes.
        Client c = client(ProviderName.INWORLD);
        transport.withResponse(200, "{\"audioContent\":\"UklGRg==\"}");
        c.speech()
                .model("inworld-tts-2").voice("Dennis")
                .generate("Hello from llmkit.");
        assertGolden("speech-inworld");
    }

    @Test
    void speechOpenAI() throws Exception {
        client(ProviderName.OPENAI).speech()
                .model("gpt-4o-mini-tts").voice("alloy")
                .generate("Hello from llmkit.");
        assertGolden("speech-openai");
    }

    // --- Video generation (ADR-034; asynchronous submit). The wire suite
    // asserts ONLY the submit body -- submit(prompt) performs the submit POST,
    // the mock answers with the provider's submit-response handle id so submit
    // returns a VideoJob (discarded), and only the captured outbound bytes are
    // asserted. The canned response carries every provider's handle field
    // (mirror of the Swift driver's videoSubmitResponse): request_id / task_id /
    // id / name / invocationArn / output.task_id / Resp.video_id.

    private static final String VIDEO_SUBMIT_RESPONSE = "{\"request_id\":\"vid_test\",\"task_id\":\"vid_test\","
            + "\"id\":\"vid_test\",\"name\":\"models/veo-test/operations/op_test\","
            + "\"invocationArn\":\"arn:test:async-invoke/op_test\","
            + "\"output\":{\"task_id\":\"vid_test\",\"task_status\":\"PENDING\"},"
            + "\"Resp\":{\"video_id\":318633193768896}}";

    private Client videoClient(ProviderName provider) {
        transport = new CapturingTransport().withResponse(200, VIDEO_SUBMIT_RESPONSE);
        return new Client(provider, "key", transport);
    }

    // VID-007: Grok video-submit body {model, prompt}.
    @Test
    void videoGrok() throws Exception {
        videoClient(ProviderName.GROK).video()
                .model("grok-imagine-video")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertGolden("video-grok");
    }

    // BUG-010: Grok image-to-video submit body {model, prompt, image:{url}} -- the
    // seed frame inlines as a data URL at image.url (the Grok image-edit encoding).
    @Test
    void videoGrokI2V() throws Exception {
        videoClient(ProviderName.GROK).video()
                .model("grok-imagine-video")
                .image("image/png", imageBytes())
                .submit("Animate the still: a slow cinematic push-in as clouds drift past the peaks");
        assertGolden("video-grok-i2v");
    }

    // ADR-034 fan-out: Zhipu CogVideoX shares the {model, prompt} arm.
    @Test
    void videoZhipu() throws Exception {
        videoClient(ProviderName.ZHIPU).video()
                .model("cogvideox-3")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertGolden("video-zhipu");
    }

    // ADR-034 fan-out: Vidu (Shengshu) shares the {model, prompt} arm.
    @Test
    void videoVidu() throws Exception {
        videoClient(ProviderName.VIDU).video()
                .model("viduq3-pro")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertGolden("video-vidu");
    }

    // ADR-034 fan-out: PixVerse's five-field body {model, prompt, duration,
    // quality, aspect_ratio}. The per-request Ai-trace-id header is a runtime
    // UUID (asserted in VideoTest, not the golden).
    @Test
    void videoPixVerse() throws Exception {
        videoClient(ProviderName.PIXVERSE).video()
                .model("v4.5")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertGolden("video-pixverse");
    }

    // ADR-034 fan-out: Together shares the {model, prompt} arm.
    @Test
    void videoTogether() throws Exception {
        videoClient(ProviderName.TOGETHER).video()
                .model("minimax/video-01-director")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertGolden("video-together");
    }

    // ADR-034 fan-out: Qwen (DashScope) nests the prompt under {model,
    // input:{prompt}} and requires the load-bearing X-DashScope-Async: enable
    // header (asserted in-driver, mirror of the Anthropic beta-header assert).
    @Test
    void videoQwen() throws Exception {
        videoClient(ProviderName.QWEN).video()
                .model("wan2.2-t2v-plus")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertEquals("enable", transport.capturedHeaders.get("X-DashScope-Async"));
        assertGolden("video-qwen");
    }

    // ADR-034 fan-out: MiniMax shares the {model, prompt} arm (the two-hop
    // result retrieve is delivery-side, covered by VideoTest).
    @Test
    void videoMiniMax() throws Exception {
        videoClient(ProviderName.MINIMAX).video()
                .model("MiniMax-Hailuo-2.3")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertGolden("video-minimax");
    }

    // ADR-034 fan-out: Google Veo carries the model in the submit PATH, so the
    // body is the nested {instances:[{prompt}]} with NO model field.
    @Test
    void videoGoogleVeo() throws Exception {
        videoClient(ProviderName.GOOGLE).video()
                .model("veo-3.1-generate-preview")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertGolden("video-google");
    }

    // ADR-034 fan-out: AWS Bedrock Nova Reel carries the model in the BODY
    // (modelId), nests the prompt under modelInput, and the caller S3 URI under
    // outputDataConfig. The submit is SigV4-signed; the mock captures the
    // outbound body regardless of the (keyless) signature.
    @Test
    void videoBedrock() throws Exception {
        videoClient(ProviderName.BEDROCK).video()
                .model("amazon.nova-reel-v1:0")
                .outputUri("s3://llmkit-wire-fixtures/out/")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertGolden("video-bedrock");
    }

    // ADR-034 delivery-mode phase: Vertex Veo's submit body is byte-identical to
    // the Gemini Veo golden (model in the PATH, not the body).
    @Test
    void videoVertexVeo() throws Exception {
        // Vertex's base carries {location}/{project_id} placeholders (the
        // caller-substituted seam); override with the mock base so the URL is
        // valid (the mock intercepts regardless; only the body is asserted).
        Client client = videoClient(ProviderName.VERTEX).baseUrl("https://mock.local");
        client.video()
                .model("veo-3.1-generate-preview")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertGolden("video-vertex");
    }

    // --- Transcription (ADR-048 / ADR-051; the Part container). Inputs
    // mirror the WIRE_TRANSCRIPTION_* wire_inputs constants.

    private static final String TRANSCRIPTION_AUDIO_URL = "https://storage.example.com/meeting-2026-06-24.mp3";
    private static final String TRANSCRIPTION_OPENAI_MODEL = "whisper-1";
    private static final String TRANSCRIPTION_OPENAI_MIME = "audio/mpeg";

    // ADR-048: AssemblyAI transcription submit body {audio_url}. The async
    // TranscriptionJob is discarded; only the outbound submit bytes are
    // asserted. The upload hop is bytes-only and is not exercised here (a URL
    // part skips it).
    @Test
    void transcriptionAssemblyAI() throws Exception {
        Client c = client(ProviderName.ASSEMBLYAI);
        transport.withResponse(200, "{\"id\":\"transcript_abc123\"}");
        c.transcription().submit(List.of(Part.audio(TRANSCRIPTION_AUDIO_URL)));
        assertGolden("transcription-assemblyai");
    }

    // ADR-051: OpenAI SYNCHRONOUS transcription is the first multipart/form-data
    // request body in the SDK. The golden is the canonical multipart descriptor
    // (OQ-3); the driver decodes its ACTUAL encoded multipart body (captured
    // bytes + boundary from the Content-Type header) into ordered fields and
    // asserts value-equal to the golden.
    @Test
    void transcriptionOpenAI() throws Exception {
        Client c = client(ProviderName.OPENAI);
        transport.withResponse(200, "{\"text\":\"Hello world.\"}");
        c.transcription()
                .model(TRANSCRIPTION_OPENAI_MODEL)
                .transcribe(List.of(
                        Part.audioBytes(TRANSCRIPTION_OPENAI_MIME, "fake-audio".getBytes(StandardCharsets.UTF_8))));
        JsonElement descriptor =
                multipartToDescriptor(transport.capturedBody, transport.capturedHeaders.get("content-type"));
        assertGolden("transcription-openai", descriptor);
    }

    /**
     * Decodes an encoded {@code multipart/form-data} body into the canonical
     * descriptor the cross-SDK comparator asserts (ADR-051 OQ-3): an ordered
     * list of form fields. Text fields emit {@code {name, value}}; the file
     * part keeps its filename + content-type but its bytes become the fixed
     * sentinel {@code "<audio-bytes>"}. Parses the ACTUAL captured bytes +
     * boundary, keeping the descriptor independent of the golden. Port of the
     * Rust {@code multipart_to_descriptor} (request_wire.rs).
     */
    static JsonElement multipartToDescriptor(String body, String contentType) {
        String boundary = "";
        int boundaryIdx = contentType.indexOf("boundary=");
        if (boundaryIdx >= 0) {
            boundary = contentType.substring(boundaryIdx + "boundary=".length()).trim();
        }
        String delim = "--" + boundary;
        JsonArray fields = new JsonArray();
        for (String rawSeg : body.split(Pattern.quote(delim), -1)) {
            String seg = rawSeg;
            if (seg.startsWith("\r\n")) {
                seg = seg.substring(2);
            }
            if (seg.isEmpty() || seg.startsWith("--")) {
                continue; // preamble or closing delimiter
            }
            int sep = seg.indexOf("\r\n\r\n");
            if (sep < 0) {
                continue;
            }
            String headerBlock = seg.substring(0, sep);
            String value = seg.substring(sep + 4);
            if (value.endsWith("\r\n")) {
                value = value.substring(0, value.length() - 2);
            }
            String name = "";
            String filename = null;
            String partContentType = null;
            for (String header : headerBlock.split("\r\n")) {
                String lower = header.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-disposition:")) {
                    name = extractQuoted(header, "name=");
                    filename = extractQuotedOrNull(header, "filename=");
                } else if (lower.startsWith("content-type:")) {
                    int colon = header.indexOf(':');
                    partContentType = header.substring(colon + 1).trim();
                }
            }
            JsonObject field = new JsonObject();
            if (filename != null) {
                field.addProperty("name", name);
                field.addProperty("filename", filename);
                field.addProperty("contentType", partContentType != null ? partContentType : "");
                field.addProperty("bytes", "<audio-bytes>");
            } else {
                field.addProperty("name", name);
                field.addProperty("value", value);
            }
            fields.add(field);
        }
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("_encoding", "multipart/form-data");
        descriptor.add("fields", fields);
        return descriptor;
    }

    /**
     * Pulls the quoted value following {@code key} (e.g. {@code
     * name="model"} -> "model"). Leftmost match, so {@code name=} on the file
     * part resolves the standalone field name, not the tail of {@code
     * filename=}.
     */
    private static String extractQuoted(String haystack, String key) {
        String value = extractQuotedOrNull(haystack, key);
        return value != null ? value : "";
    }

    private static String extractQuotedOrNull(String haystack, String key) {
        int start = haystack.indexOf(key);
        if (start < 0) {
            return null;
        }
        int quoteStart = start + key.length();
        if (quoteStart >= haystack.length() || haystack.charAt(quoteStart) != '"') {
            return null;
        }
        int quoteEnd = haystack.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) {
            return null;
        }
        return haystack.substring(quoteStart + 1, quoteEnd);
    }

    // --- Bedrock Converse (SigV4 signing; body is asserted, signature is not).
    // AWS_REGION / AWS_SECRET_ACCESS_KEY are deterministic dummies supplied by
    // the Gradle test task (Java cannot setenv at runtime); the signature is
    // time-dependent and NOT asserted, only the body is. ---

    @Test
    void bedrockChat() throws Exception {
        client(ProviderName.BEDROCK).text()
                .maxTokens(256).temperature(0.7).topP(0.9).stopSequences(List.of("END_OF_ANSWER"))
                .prompt("Name the capital of Finland in one word, then write END_OF_ANSWER.");
        assertGolden("bedrock-chat");
    }
}
