package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Caching;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import com.aktagon.llmkit.providers.generated.ResponsePaths;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;

/*






*/
final class ResponseParser {
    private ResponseParser() {}

    static Response parse(Providers.Spec config, byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        JsonElement raw = Json.parse(text);

        Caching.UsagePaths cachePaths = Caching.usagePaths(config.name);
        String costPath = ResponsePaths.usageCostPath(config.name);
        Usage usage = new Usage(
                Json.longAt(raw, config.usageInputPath),
                Json.longAt(raw, config.usageOutputPath),
                cachePaths.write.isEmpty() ? 0 : Json.longAt(raw, cachePaths.write),
                cachePaths.read.isEmpty() ? 0 : Json.longAt(raw, cachePaths.read),
                config.reasoningTokensPath.isEmpty() ? 0 : Json.longAt(raw, config.reasoningTokensPath),
                costPath.isEmpty()
                        ? 0.0
                        : Json.doubleAt(raw, costPath) * ResponsePaths.usageCostScale(config.name));

        return new Response(
                Json.stringAt(raw, config.responseTextPath),
                usage,
                config.finishReasonPath.isEmpty() ? "" : Json.stringAt(raw, config.finishReasonPath),
                config.finishMessagePath.isEmpty() ? "" : Json.stringAt(raw, config.finishMessagePath),
                null);
    }

    /*



*/
    static ApiException parseError(Providers.Spec config, int statusCode, byte[] body) {
        String raw = new String(body, StandardCharsets.UTF_8);
        String message = raw;
        if (!config.errorMessagePath.isEmpty()) {
            try {
                String extracted = Json.stringAt(Json.parse(raw), config.errorMessagePath);
                if (!extracted.isEmpty()) {
                    message = extracted;
                }
            } catch (DecodingException ignored) {
                //
            }
        }
        return new ApiException(config.slug, statusCode, message);
    }
}
