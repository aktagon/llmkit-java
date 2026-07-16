package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;

/**
 * Parses a provider chat response into the universal {@code Response},
 * reading every field from the per-provider dotted paths declared on the
 * generated {@code Providers.Spec}. Mirrors Swift's ResponseParser / Rust's
 * {@code parse_response_shaped}.
 */
final class ResponseParser {
    private ResponseParser() {}

    static Response parse(Providers.Spec config, byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        JsonElement raw = Json.parse(text);

        Usage usage = new Usage(
                Json.longAt(raw, config.usageInputPath),
                Json.longAt(raw, config.usageOutputPath),
                config.cacheWritePath.isEmpty() ? 0 : Json.longAt(raw, config.cacheWritePath),
                config.cacheReadPath.isEmpty() ? 0 : Json.longAt(raw, config.cacheReadPath),
                config.reasoningTokensPath.isEmpty() ? 0 : Json.longAt(raw, config.reasoningTokensPath),
                config.usageCostPath.isEmpty()
                        ? 0.0
                        : Json.doubleAt(raw, config.usageCostPath) * config.usageCostScale);

        return new Response(
                Json.stringAt(raw, config.responseTextPath),
                usage,
                config.finishReasonPath.isEmpty() ? "" : Json.stringAt(raw, config.finishReasonPath),
                config.finishMessagePath.isEmpty() ? "" : Json.stringAt(raw, config.finishMessagePath),
                null);
    }

    /**
     * Map a non-2xx response to a typed API error. Phase 0 surfaces the raw
     * body as the message; per-provider error-path parsing lands in Phase 2.
     */
    static ApiException parseError(Providers.Spec config, int statusCode, byte[] body) {
        return new ApiException(
                config.slug, statusCode, new String(body, StandardCharsets.UTF_8));
    }
}
