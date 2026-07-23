package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Caching;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import com.aktagon.llmkit.providers.generated.ResponsePaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;













public final class ResponseCodec {
    private ResponseCodec() {}














    public static Response decodeResponse(
            ProviderName provider, String chatWireShape, byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        JsonElement raw = Json.parse(text);

        if ("ChatResponsesOpenAI".equals(chatWireShape)) {
            return parseResponsesEnvelope(raw);
        }

        Providers.Spec config = Providers.config(provider);
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














    public static byte[] encodeResponse(
            ProviderName provider, String chatWireShape, Response response) {
        guardOneWayFields(provider, response);
        if ("ChatResponsesOpenAI".equals(chatWireShape)) {
            return serialize(encodeResponsesEnvelope(response));
        }

        Providers.Spec config = Providers.config(provider);
        Caching.UsagePaths cachePaths = Caching.usagePaths(config.name);
        double costScale = ResponsePaths.usageCostScale(config.name);

        JsonObject raw = new JsonObject();
        JsonBuilder.setWirePath(raw, config.responseTextPath, new JsonPrimitive(response.text()));
        JsonBuilder.setWirePath(raw, config.usageInputPath, new JsonPrimitive(response.usage().input()));
        JsonBuilder.setWirePath(raw, config.usageOutputPath, new JsonPrimitive(response.usage().output()));
        JsonBuilder.setWirePath(raw, cachePaths.write, new JsonPrimitive(response.usage().cacheWrite()));
        JsonBuilder.setWirePath(raw, cachePaths.read, new JsonPrimitive(response.usage().cacheRead()));
        JsonBuilder.setWirePath(
                raw, config.reasoningTokensPath, new JsonPrimitive(response.usage().reasoning()));
        if (costScale != 0) {
            JsonBuilder.setWirePath(
                    raw,
                    ResponsePaths.usageCostPath(config.name),
                    new JsonPrimitive(response.usage().cost() / costScale));
        }
        JsonBuilder.setWirePath(raw, config.finishReasonPath, new JsonPrimitive(response.finishReason()));
        JsonBuilder.setWirePath(raw, config.finishMessagePath, new JsonPrimitive(response.finishMessage()));
        return serialize(raw);
    }













    static void guardOneWayFields(ProviderName provider, Response response) {
        if (provider == ProviderName.VERTEX && !response.finishReason().isEmpty()) {
            throw new ValidationException(
                    "response.finish_reason",
                    "Vertex carries no finish-reason field. Its path reads predictions[0].raiFilteredReason — a safety-filter explanation surfaced AS the finish reason. Extraction is a deliberate fusion, so the reverse leg cannot decide whether a given canonical finish_reason originated as a safety verdict, and writing an ordinary stop signal into that field would fabricate one.");
        }
    }










    static Response parseResponsesEnvelope(JsonElement raw) {
        Usage usage = new Usage(
                Json.longAt(raw, "usage.input_tokens"),
                Json.longAt(raw, "usage.output_tokens"),
                0,
                Json.longAt(raw, "usage.input_tokens_details.cached_tokens"),
                Json.longAt(raw, "usage.output_tokens_details.reasoning_tokens"),
                0.0);
        return new Response(extractResponsesText(raw), usage, Json.stringAt(raw, "status"), "", null);
    }






    static JsonObject encodeResponsesEnvelope(Response response) {
        JsonObject raw = new JsonObject();
        if (!response.text().isEmpty()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "output_text");
            block.addProperty("text", response.text());
            JsonArray content = new JsonArray();
            content.add(block);
            JsonObject message = new JsonObject();
            message.addProperty("type", "message");
            message.add("content", content);
            JsonArray output = new JsonArray();
            output.add(message);
            raw.add("output", output);
        }
        JsonBuilder.setWirePath(raw, "usage.input_tokens", new JsonPrimitive(response.usage().input()));
        JsonBuilder.setWirePath(raw, "usage.output_tokens", new JsonPrimitive(response.usage().output()));
        JsonBuilder.setWirePath(
                raw,
                "usage.input_tokens_details.cached_tokens",
                new JsonPrimitive(response.usage().cacheRead()));
        JsonBuilder.setWirePath(
                raw,
                "usage.output_tokens_details.reasoning_tokens",
                new JsonPrimitive(response.usage().reasoning()));
        JsonBuilder.setWirePath(raw, "status", new JsonPrimitive(response.finishReason()));
        return raw;
    }







    static String extractResponsesText(JsonElement raw) {
        JsonElement output = Json.at(raw, "output");
        if (output == null || !output.isJsonArray()) {
            return "";
        }
        for (JsonElement item : output.getAsJsonArray()) {
            if (!item.isJsonObject() || !"message".equals(Json.stringAt(item, "type"))) {
                continue;
            }
            JsonElement content = item.getAsJsonObject().get("content");
            if (content == null || !content.isJsonArray()) {
                continue;
            }
            for (JsonElement block : content.getAsJsonArray()) {
                if (block.isJsonObject() && "output_text".equals(Json.stringAt(block, "type"))) {
                    return Json.stringAt(block, "text");
                }
            }
        }
        return "";
    }

    private static byte[] serialize(JsonObject raw) {
        return Json.serialize(raw).getBytes(StandardCharsets.UTF_8);
    }






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
