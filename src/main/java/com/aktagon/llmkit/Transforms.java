package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Providers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Request-body transforms, selected by the generated {@code chatWireShape}
 * fact (ADR-047 / ADR-055 discriminator), NOT by provider name. Phase 0
 * implements only the flat {@code ChatOpenAI} shape (single-turn user
 * prompt); other shapes are rejected until their slice lands.
 */
final class Transforms {
    private Transforms() {}

    /**
     * Append the provider-specific messages array to {@code body}. Phase 0:
     * a single user turn plus, when the provider places system content in
     * the message array, a leading system turn.
     */
    static void applyMessageShape(
            JsonObject body, String userPrompt, String system, Providers.Spec config) {
        JsonArray messages = new JsonArray();
        if ("MessageInArray".equals(config.systemPlacement)
                && system != null && !system.isEmpty()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", mapRole("system", config));
            systemMessage.addProperty("content", system);
            messages.add(systemMessage);
        }
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", mapRole("user", config));
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);
        body.add("messages", messages);
    }

    /**
     * Translate a canonical role to the provider's wire role (identity when
     * the provider declares no mapping).
     */
    static String mapRole(String role, Providers.Spec config) {
        return config.roleMappings.getOrDefault(role, role);
    }
}
