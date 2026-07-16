package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Providers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Request-body message transforms, selected by the effective
 * {@code chatWireShape} (ADR-047 / ADR-055 discriminator), NOT by provider
 * name — a port of Swift's {@code Transforms} / Rust's
 * {@code transforms.rs::apply_message_shape}. Phase 2 covers the single-turn
 * user path for the chat array shapes: Google {@code contents}/{@code parts},
 * OpenAI Responses {@code input}, Bedrock {@code [{text}]} content, and the flat
 * {@code messages} shape (OpenAI/Anthropic). Multi-turn history, media Parts,
 * and tool defs land in later phases.
 */
final class Transforms {
    private Transforms() {}

    /** Append the provider-specific message array to {@code body}. */
    static void applyMessageShape(
            JsonObject body, String userPrompt, String system, String wireShape, Providers.Spec config) {
        if ("ChatGoogle".equals(wireShape)) {
            transformGoogleParts(body, userPrompt, config);
        } else if ("ChatResponsesOpenAI".equals(wireShape)) {
            body.add("input", flatMessageArray(userPrompt, system, wireShape, config));
        } else {
            body.add("messages", flatMessageArray(userPrompt, system, wireShape, config));
        }
    }

    /**
     * The shared flat message array used by both the Chat Completions
     * ({@code messages}) and Responses ({@code input}) envelopes. A leading
     * system turn is emitted only for the MessageInArray placement; Bedrock
     * wraps content in a {@code [{text}]} block.
     */
    private static JsonArray flatMessageArray(
            String userPrompt, String system, String wireShape, Providers.Spec config) {
        boolean bedrock = "ChatBedrock".equals(wireShape);
        JsonArray messages = new JsonArray();

        if ("MessageInArray".equals(config.systemPlacement) && system != null && !system.isEmpty()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", mapRole("system", config));
            systemMessage.addProperty("content", system);
            messages.add(systemMessage);
        }

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", mapRole("user", config));
        if (bedrock) {
            JsonObject block = new JsonObject();
            block.addProperty("text", userPrompt);
            JsonArray content = new JsonArray();
            content.add(block);
            userMessage.add("content", content);
        } else {
            userMessage.addProperty("content", userPrompt);
        }
        messages.add(userMessage);
        return messages;
    }

    private static void transformGoogleParts(JsonObject body, String userPrompt, Providers.Spec config) {
        JsonObject part = new JsonObject();
        part.addProperty("text", userPrompt);
        JsonArray parts = new JsonArray();
        parts.add(part);

        JsonObject content = new JsonObject();
        content.addProperty("role", mapRole("user", config));
        content.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(content);
        body.add("contents", contents);
    }

    /**
     * Translate a canonical role to the provider's wire role (identity when the
     * provider declares no mapping).
     */
    static String mapRole(String role, Providers.Spec config) {
        return config.roleMappings.getOrDefault(role, role);
    }
}
