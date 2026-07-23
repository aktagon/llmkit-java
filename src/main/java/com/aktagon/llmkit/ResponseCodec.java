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

/**
 * The symmetric response codec (ADR-076): one keyless decode/encode pair over
 * the per-provider dotted paths declared on the generated
 * {@code Providers.Spec} (text/usage/finish paths) plus the
 * {@code Caching}/{@code ResponsePaths} fact tables (cache and cost paths,
 * Phase 1). Mirrors Go's {@code DecodeResponse}/{@code EncodeResponse} and
 * Swift's {@code decodeResponse}/{@code encodeResponse}.
 *
 * <p>Java has no free functions, so ADR-076 §2 hosts the pair on this class —
 * the former {@code ResponseParser}, renamed because "parser" became a lie once
 * it gained a second direction.
 */
public final class ResponseCodec {
    private ResponseCodec() {}

    /**
     * Extracts text and usage from a provider response body into the universal
     * {@code Response}. {@code chatWireShape} is the EFFECTIVE wire shape for
     * this request (after {@code protocol(...)} resolution, ADR-055): only
     * {@code ChatResponsesOpenAI} diverges (the {@code output[]} envelope);
     * every other value uses the provider's declared response paths.
     *
     * <p>Keyless, IO-free and pure (ADR-076 SYM-002): no {@code Client}, no
     * credential, no network, no clock. The wire shape is required, not derived
     * — one provider can serve two chat protocols, and inferring it silently
     * mis-parses (SYM-003). This is the same function the chat send path calls
     * (SYM-004).
     */
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

    /**
     * {@code decodeResponse}'s inverse: renders a canonical {@code Response}
     * back onto the wire for {@code provider} + {@code chatWireShape}. Every
     * write location comes from the same generated path accessors
     * {@code decodeResponse} reads — there is no second table and no path
     * literal here (ADR-076 SYM-005).
     *
     * <p>Keyless, IO-free and pure, like its mirror. The result is NOT
     * byte-identical to the body a provider would send: a provider body carries
     * fields the canonical {@code Response} does not model (ADR-014's
     * {@code raw} exists for exactly that). The contract is the canonical fixed
     * point, {@code decode(encode(decode(b))) == decode(b)} (SYM-006).
     */
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

    /**
     * Refuses to encode a canonical field whose mapping is {@code OneWay}
     * for this provider — the result set of CQ-WMAP-011 (ADR-076 SYM-007). Only
     * one member is in Phase 2's scope; the other,
     * {@code exceptGoogleToolCallID}, covers tool calls, which are out
     * (SYM-008).
     *
     * <p>An empty value is not an error: there is nothing to write, so the
     * common path stays usable and only the lying path fails. The field and
     * message carry the mapping's {@code canonicalPath} and
     * {@code invertibilityNote} verbatim.
     */
    static void guardOneWayFields(ProviderName provider, Response response) {
        if (provider == ProviderName.VERTEX && !response.finishReason().isEmpty()) {
            throw new ValidationException(
                    "response.finish_reason",
                    "Vertex carries no finish-reason field. Its path reads predictions[0].raiFilteredReason — a safety-filter explanation surfaced AS the finish reason. Extraction is a deliberate fusion, so the reverse leg cannot decide whether a given canonical finish_reason originated as a safety verdict, and writing an ordinary stop signal into that field would fabricate one.");
        }
    }

    /**
     * Extracts text + usage from OpenAI's Responses reply (ADR-055). Unlike
     * Chat Completions ({@code choices[].message.content}), the reply is an
     * {@code output[]} array whose message item carries {@code content[]}
     * blocks of type "output_text"; usage is input_tokens/output_tokens with
     * cached + reasoning sub-details. Hand-coded per wire shape, symmetric with
     * the {@code transformResponsesInput} request arm (ADR-028: behavior held
     * by tests, not by declared response paths).
     */
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

    /**
     * Mirror of {@code parseResponsesEnvelope}: rebuilds OpenAI's Responses
     * reply (ADR-055). Hand-coded per wire shape on both legs, symmetric with
     * the reader, for the same reason the reader is.
     */
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

    /**
     * Walks the Responses {@code output[]} array for the first message item and
     * returns its first {@code output_text} block. Iterating (rather than a
     * fixed {@code output[0].content[0]} path) tolerates a leading reasoning
     * item.
     */
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

    /**
     * Map a non-2xx response to a typed API error, reading the message from the
     * provider's declared {@code errorMessagePath} when the body parses as JSON,
     * falling back to the raw body (mirrors Swift's {@code parseError}).
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
                // Body was not JSON; keep the raw message.
            }
        }
        return new ApiException(config.slug, statusCode, message);
    }
}
