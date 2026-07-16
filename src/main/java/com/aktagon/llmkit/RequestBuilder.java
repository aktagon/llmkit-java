package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Providers;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the provider-specific request body, headers, and URL from the
 * generated {@code Providers.Spec}. Phase 0 covers the OpenAI ChatCompletion
 * slice (non-streaming, no tools/caching).
 */
final class RequestBuilder {
    private RequestBuilder() {}

    /** One built request: ordered JSON body + auth headers. */
    record Built(JsonObject body, Map<String, String> headers) {}

    /** Construct the request body + headers for a single-turn chat request. */
    static Built buildRequest(
            Providers.Spec config,
            String apiKey,
            String model,
            String system,
            String userPrompt,
            int maxTokens) {
        if (!"ChatOpenAI".equals(config.chatWireShape)) {
            throw new ValidationException(
                    "provider",
                    "Phase 0 supports only the ChatOpenAI wire shape; got " + config.chatWireShape);
        }

        JsonObject body = Json.object();
        if (config.modelInBody) {
            body.addProperty("model", model);
        }
        body.addProperty("max_tokens", maxTokens);
        Transforms.applyMessageShape(body, userPrompt, system, config);

        return new Built(body, buildAuthHeaders(config, apiKey));
    }

    /** Provider auth headers, dispatched on the generated {@code authScheme} fact. */
    static Map<String, String> buildAuthHeaders(Providers.Spec config, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        switch (config.authScheme) {
            case "BearerToken" -> headers.put(config.authHeader, config.authPrefix + " " + apiKey);
            case "HeaderApiKey" -> headers.put(config.authHeader, apiKey);
            default -> { }
        }
        return headers;
    }

    /** The request URL: base (with optional override) + endpoint. */
    static String buildUrl(Providers.Spec config, String baseUrlOverride) {
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        return base + config.endpoint;
    }
}
