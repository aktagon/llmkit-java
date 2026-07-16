package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Providers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;







final class Transforms {
    private Transforms() {}






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





    static String mapRole(String role, Providers.Spec config) {
        return config.roleMappings.getOrDefault(role, role);
    }
}
