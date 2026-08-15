package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Caching;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.ProviderTurn;
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
    /**
     * Fill in an unspecified wire shape with the provider's DEFAULT chat protocol.
     *
     * <p>Callers that decode a body they know is Chat Completions — batch result
     * lines, chiefly — pass "" to mean "not the Responses envelope". Harmless
     * while the shape only chose between the Responses arm and the provider's
     * declared paths; NOT harmless once it also selects the TEXT READER, because
     * "" resolved to no config, which is the positional reader BUG-053 removed.
     * Batched Anthropic replies with a leading thinking block decoded to "" long
     * after the send path was fixed.
     *
     * <p>Resolving here keeps N=1. ADR-055 requires every provider's default
     * protocol to be a Chat Completions family, so this can never resolve INTO
     * the Responses arm.
     */
    static String resolveChatWireShape(ProviderName provider, String chatWireShape) {
        return chatWireShape == null || chatWireShape.isEmpty()
                ? Providers.config(provider).chatWireShape
                : chatWireShape;
    }

    public static Response decodeResponse(
            ProviderName provider, String rawWireShape, byte[] body) {
        String chatWireShape = resolveChatWireShape(provider, rawWireShape);
        String text = new String(body, StandardCharsets.UTF_8);
        JsonElement raw = Json.parse(text);
        Providers.Spec config = Providers.config(provider);
        // ADR-085: capture the assistant turn as the provider serialized it, from
        // the ORIGINAL text rather than from `raw` — re-serializing the parsed tree
        // would emit Gson's rendering, not the provider's.
        ProviderTurn providerTurn = ProviderTurnCapture.capture(text, config, chatWireShape);

        if ("ChatResponsesOpenAI".equals(chatWireShape)) {
            Response envelope = parseResponsesEnvelope(raw);
            return new Response(
                    envelope.text(),
                    envelope.usage(),
                    envelope.finishReason(),
                    envelope.finishMessage(),
                    envelope.raw(),
                    providerTurn);
        }

        return new Response(
                extractResponseText(raw, config, chatWireShape),
                decodeUsage(raw, provider),
                Json.optString(raw, config.finishReasonPath),
                Json.optString(raw, config.finishMessagePath),
                null,
                providerTurn);
    }

    /**
     * Reads every canonical {@code Usage} dimension out of a provider response
     * body. The ONE usage reader (ADR-076 SYM-004): the codec, the chat send
     * path and the agent loop all call this, so a dimension cannot be read in
     * one place and forgotten in another — which is how the agent loop came to
     * accumulate a subset of the six across the SDK pack (BUG-045).
     *
     * <p>A dimension is null when the provider declares no path for it OR the
     * response did not carry it. Neither is zero.
     */
    static Usage decodeUsage(JsonElement raw, ProviderName provider) {
        Providers.Spec config = Providers.config(provider);
        Caching.UsagePaths cachePaths = Caching.usagePaths(config.name);
        Double cost = Json.optDouble(raw, ResponsePaths.usageCostPath(config.name));
        return new Usage(
                Json.optLong(raw, config.usageInputPath),
                Json.optLong(raw, config.usageOutputPath),
                Json.optLong(raw, cachePaths.write),
                Json.optLong(raw, cachePaths.read),
                Json.optLong(raw, config.reasoningTokensPath),
                // Scaling PRESERVES absence: an unreported cost stays
                // unreported rather than becoming 0.0 * scale (AVAIL-007).
                cost == null ? null : cost * ResponsePaths.usageCostScale(config.name));
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
            ProviderName provider, String rawWireShape, Response response) {
        String chatWireShape = resolveChatWireShape(provider, rawWireShape);
        guardOneWayFields(provider, response);
        if ("ChatResponsesOpenAI".equals(chatWireShape)) {
            return serialize(encodeResponsesEnvelope(response));
        }

        Providers.Spec config = Providers.config(provider);
        Caching.UsagePaths cachePaths = Caching.usagePaths(config.name);
        double costScale = ResponsePaths.usageCostScale(config.name);

        JsonObject raw = new JsonObject();
        encodeResponseText(raw, config, chatWireShape, response.text());
        JsonBuilder.setWirePath(raw, config.usageInputPath, JsonBuilder.wire(response.usage().input()));
        JsonBuilder.setWirePath(raw, config.usageOutputPath, JsonBuilder.wire(response.usage().output()));
        JsonBuilder.setWirePath(raw, cachePaths.write, JsonBuilder.wire(response.usage().cacheWrite()));
        JsonBuilder.setWirePath(raw, cachePaths.read, JsonBuilder.wire(response.usage().cacheRead()));
        JsonBuilder.setWirePath(
                raw, config.reasoningTokensPath, JsonBuilder.wire(response.usage().reasoning()));
        if (costScale != 0) {
            Double cost = response.usage().cost();
            JsonBuilder.setWirePath(
                    raw,
                    ResponsePaths.usageCostPath(config.name),
                    JsonBuilder.wire(cost == null ? null : cost / costScale));
        }
        JsonBuilder.setWirePath(raw, config.finishReasonPath, JsonBuilder.wire(response.finishReason()));
        JsonBuilder.setWirePath(raw, config.finishMessagePath, JsonBuilder.wire(response.finishMessage()));
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
        // Non-empty, not merely present: the guard exists to refuse
        // FABRICATION, and neither an unreported field nor a reported empty one
        // would write anything (see isEmptyWireValue). Only a value that would
        // reach the wire lies.
        if (provider == ProviderName.VERTEX
                && response.finishReason() != null
                && !response.finishReason().isEmpty()) {
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
        // The Responses envelope carries no cache-write or cost field at all.
        // That is not a zero: it is the provider never making the claim.
        Usage usage = new Usage(
                Json.optLong(raw, "usage.input_tokens"),
                Json.optLong(raw, "usage.output_tokens"),
                null,
                Json.optLong(raw, "usage.input_tokens_details.cached_tokens"),
                Json.optLong(raw, "usage.output_tokens_details.reasoning_tokens"),
                null);
        return new Response(
                extractResponsesText(raw), usage, Json.optString(raw, "status"), null, null, null);
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
        JsonBuilder.setWirePath(raw, "usage.input_tokens", JsonBuilder.wire(response.usage().input()));
        JsonBuilder.setWirePath(raw, "usage.output_tokens", JsonBuilder.wire(response.usage().output()));
        JsonBuilder.setWirePath(
                raw,
                "usage.input_tokens_details.cached_tokens",
                JsonBuilder.wire(response.usage().cacheRead()));
        JsonBuilder.setWirePath(
                raw,
                "usage.output_tokens_details.reasoning_tokens",
                JsonBuilder.wire(response.usage().reasoning()));
        JsonBuilder.setWirePath(raw, "status", JsonBuilder.wire(response.finishReason()));
        return raw;
    }

    /**
     * Reads the assistant's text out of a parsed provider body.
     *
     * <p>Two readers, selected by the WIRE SHAPE, never by provider name:
     * block-array families declare a {@code responseTextConfig} and are read by
     * DISCRIMINATOR, because array position is not stable — Opus 5 and Sonnet 5
     * think by default, so {@code content[0]} is a thinking block (BUG-053);
     * scalar families declare none, and null SELECTS the fixed-path reader.
     *
     * <p>An empty result is a real answer, not a failure: every tool-use turn
     * carries no text block at all. {@code finishReason} is what says why.
     */
    static String extractResponseText(
            JsonElement raw, Providers.Spec config, String chatWireShape) {
        ResponsePaths.ResponseTextConfig textConfig =
                ResponsePaths.responseTextConfig(chatWireShape);
        if (textConfig == null) {
            return Json.stringAt(raw, config.responseTextPath);
        }
        java.util.List<JsonObject> blocks =
                Json.matchingBlocks(
                        raw, textConfig.blocksPath, textConfig.markerPath, textConfig.markerValue);
        if (blocks.isEmpty()) {
            return "";
        }
        return Json.stringAt(blocks.get(0), textConfig.valuePath);
    }

    /**
     * {@link #extractResponseText}'s inverse, driven by the SAME config so the
     * two cannot drift apart.
     *
     * <p>The marker is WRITTEN, not just tested. Emitting only the value path
     * would produce {@code {"content":[{"text":"pong"}]}} — a body with no type
     * discriminator, which the reader above then finds no matching block in.
     * That is the ADR-076 fixed point breaking, and it is why
     * {@code textMarkerValue} is documented as a write instruction rather
     * than a read predicate.
     */
    static void encodeResponseText(
            JsonObject raw, Providers.Spec config, String chatWireShape, String text) {
        ResponsePaths.ResponseTextConfig textConfig =
                ResponsePaths.responseTextConfig(chatWireShape);
        if (textConfig == null) {
            JsonBuilder.setWirePath(raw, config.responseTextPath, new JsonPrimitive(text));
            return;
        }
        String block = textConfig.blocksPath + "[0]";
        if (!textConfig.markerValue.isEmpty()) {
            JsonBuilder.setWirePath(
                    raw,
                    block + "." + textConfig.markerPath,
                    new JsonPrimitive(textConfig.markerValue));
        }
        JsonBuilder.setWirePath(raw, block + "." + textConfig.valuePath, new JsonPrimitive(text));
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
