package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.util.List;
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
        JsonElement body = Json.parse(transport.capturedBody);
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
