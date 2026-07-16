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
}
