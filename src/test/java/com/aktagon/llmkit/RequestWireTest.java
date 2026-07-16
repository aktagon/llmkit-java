package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Request-wire driver (ADR-028 direction): build an OpenAI chat request
 * through the full SDK stack, capture the outbound bytes via the injected
 * transport, and assert the request body. Java-local this slice — full
 * cross-SDK PER_SDK enrollment is the phase-2 exit gate (mirrors the Swift
 * Phase 0 driver).
 */
class RequestWireTest {

    @Test
    void openAiChatBasicRequestBody() throws Exception {
        // A canned 200 lets prompt() complete; its content is irrelevant to
        // the request-side assertion.
        CapturingTransport transport = new CapturingTransport().withResponse(
                200, TestPaths.read(TestPaths.testdata("wire/response/v1/bodies/chat-openai.json")));

        Client client = new Client(ProviderName.OPENAI, "sk-test-key", transport);
        client.text()
                .model("gpt-4o")
                .maxTokens(256)
                .prompt("Reply with the single word: pong.");

        JsonElement captured = Json.parse(transport.capturedBody);
        TestPaths.writeRequestArtifact("chat-openai-basic", captured);

        JsonObject expected = new JsonObject();
        expected.addProperty("model", "gpt-4o");
        expected.addProperty("max_tokens", 256);
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "Reply with the single word: pong.");
        messages.add(user);
        expected.add("messages", messages);

        assertEquals(expected, captured);
        assertEquals("https://api.openai.com/v1/chat/completions", transport.capturedUrl);
        assertEquals("Bearer sk-test-key", transport.capturedHeaders.get("Authorization"));
    }
}
