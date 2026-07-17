package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.AudioData;
import com.aktagon.llmkit.providers.generated.MusicGenDef;
import com.aktagon.llmkit.providers.generated.MusicModelDef;
import com.aktagon.llmkit.providers.generated.MusicResponse;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Immutable, clone-on-chain builder for music generation (ADR-068 Phase 4d,
 * ADR-033/037 discipline) — a port of Swift's {@code Music} / Rust's
 * {@code music.rs}. Synchronous: {@code client.music().<config>.generate(prompt)}
 * builds the provider request body, sends it once, and parses the reply into
 * the universal {@link MusicResponse}.
 *
 * <p>Dispatch branches on the generated {@code musicGenConfig(provider).wireShape}
 * — never the provider name — which fully determines the request body, the
 * response audio path, AND the byte encoding (base64 vs hex):
 *
 * <ul>
 *   <li>{@code MusicPredict} (Vertex Lyria): instances/parameters envelope to
 *       {@code :predict}; audio at {@code predictions[].audioContent} (base64 WAV).
 *   <li>{@code MusicMinimax}: top-level model/prompt/lyrics/audio_setting to
 *       the absolute gen endpoint; audio at {@code data.audio} (hex).
 *   <li>{@code MusicGenerateContent} (Gemini Lyria 3): prompt + lyrics fold
 *       into {@code contents[0].parts[].text} with
 *       {@code responseModalities=["AUDIO"]}; audio at
 *       {@code candidates[0].content.parts[].inlineData.data} (base64).
 * </ul>
 *
 * <p>Lyrics support is advisory (ADR-037 MUS-008), not gated: lyrics fold into
 * the prompt for the Predict shape and the model ignores or honors them.
 * Fires the {@code musicGeneration} middleware op pre + post.
 */
public final class Music {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    private final MusicOptions options;
    /** Accumulated text + lyrics parts in caller order (the canonical sequence path). */
    private final List<Part> parts;

    private Music(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            MusicOptions options,
            List<Part> parts) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.options = options;
        this.parts = parts;
    }

    static Music root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Music(provider, apiKey, baseUrlOverride, http, null, new MusicOptions(), List.of());
    }

    /**
     * Select the music-generation model (required — the text-generation
     * default does not generate audio).
     */
    public Music model(String model) {
        return new Music(provider, apiKey, baseUrlOverride, http, model, options, parts);
    }

    /**
     * Append a text part (ordered). Mixing {@code .text(...)} /
     * {@code .lyrics(...)} uses the canonical parts path; a final
     * {@code generate(prompt)} appends the prompt as a last text part.
     */
    public Music text(String value) {
        List<Part> next = new ArrayList<>(parts);
        next.add(new Part.Text(value));
        return new Music(provider, apiKey, baseUrlOverride, http, model, options, List.copyOf(next));
    }

    /**
     * Append a lyrics part (ordered). Advisory (ADR-037 MUS-008):
     * instrumental models fold lyrics into the prompt and ignore them.
     */
    public Music lyrics(String value) {
        List<Part> next = new ArrayList<>(parts);
        next.add(new Part.Lyrics(value));
        return new Music(provider, apiKey, baseUrlOverride, http, model, options, List.copyOf(next));
    }

    /** Opt into {@code MusicResponse.raw} (the parsed provider body, ADR-014). */
    public Music raw() {
        return withOptions(o -> o.raw = true);
    }

    /** Register a middleware hook (observation + pre-phase veto). */
    public Music addMiddleware(MiddlewareFn hook) {
        return withOptions(o -> o.middleware.add(hook));
    }

    /**
     * Build and send the music request, returning the decoded
     * {@link MusicResponse}. {@code prompt} is terse sugar for the
     * prompt-only hot path; when the chain accumulated {@code .text(...)} /
     * {@code .lyrics(...)} parts, a non-empty {@code prompt} is appended as a
     * final text part. Fires the {@code musicGeneration} middleware op
     * (pre-phase veto, post-phase observation with usage).
     */
    public MusicResponse generate(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ValidationException("api_key", "required");
        }
        if (model == null || model.isEmpty()) {
            throw new ValidationException("model", "required for music generation");
        }
        List<Part> normalized = normalizeParts(prompt);

        Providers.Spec config = Providers.config(provider);
        MusicGenDef mgCfg = MusicGenDef.musicGenConfig(provider);
        if (mgCfg == null) {
            throw new ValidationException("provider", config.slug + " does not support music generation");
        }
        MusicModelDef modelDef = findModel(mgCfg, model);
        if (modelDef == null) {
            throw new ValidationException(
                    "model", model + " is not a known music-generation model for " + config.slug);
        }

        Event baseEvent = Event.of(MiddlewareOp.MUSIC_GENERATION, config.slug, model);
        long startNanos = System.nanoTime();
        Middleware.firePre(options.middleware, baseEvent);

        try {
            MusicResponse response = send(normalized, modelDef, mgCfg, config);
            Middleware.firePost(
                    options.middleware,
                    baseEvent.toPost("", response.usage(), null, Middleware.elapsedMillis(startNanos)));
            return response;
        } catch (RuntimeException e) {
            Middleware.firePost(
                    options.middleware,
                    baseEvent.toPost("", null, e.getMessage(), Middleware.elapsedMillis(startNanos)));
            throw e;
        }
    }

    /** Clone-on-chain: copy the options, mutate, return a fresh builder. */
    private Music withOptions(Consumer<MusicOptions> mutate) {
        MusicOptions copy = options.copy();
        mutate.accept(copy);
        return new Music(provider, apiKey, baseUrlOverride, http, model, copy, parts);
    }

    // --- Parts ---

    /**
     * The internal music-input atom: text or lyrics. A music request never
     * carries image parts — the {@code Part} type makes that unrepresentable
     * (the Rust runtime rejects {@code Part::Image}; here the case does not
     * exist).
     */
    private sealed interface Part {
        record Text(String text) implements Part {}

        record Lyrics(String text) implements Part {}
    }

    /**
     * Enforce the prompt-XOR-parts rule and produce the canonical part list.
     * When the chain accumulated parts, a non-empty {@code prompt} appends as
     * a final text part; otherwise the prompt sugar path. Both empty is a
     * validation error (mirror of {@code normalize_music_parts}).
     */
    private List<Part> normalizeParts(String prompt) {
        if (!parts.isEmpty()) {
            List<Part> out = new ArrayList<>(parts);
            if (prompt != null && !prompt.isEmpty()) {
                out.add(new Part.Text(prompt));
            }
            return out;
        }
        if (prompt == null || prompt.isEmpty()) {
            throw new ValidationException("prompt", "set either prompt or parts");
        }
        return List.of(new Part.Text(prompt));
    }

    private static String joinPromptText(List<Part> parts) {
        List<String> texts = new ArrayList<>();
        for (Part part : parts) {
            if (part instanceof Part.Text text && !text.text().isEmpty()) {
                texts.add(text.text());
            }
        }
        return String.join("\n", texts);
    }

    private static String joinLyricsText(List<Part> parts) {
        List<String> texts = new ArrayList<>();
        for (Part part : parts) {
            if (part instanceof Part.Lyrics lyrics && !lyrics.text().isEmpty()) {
                texts.add(lyrics.text());
            }
        }
        return String.join("\n", texts);
    }

    // --- Send ---

    private MusicResponse send(List<Part> parts, MusicModelDef modelDef, MusicGenDef mgCfg, Providers.Spec config) {
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

        String url;
        JsonObject body;
        switch (mgCfg.wireShape()) {
            case "MusicPredict" -> {
                String endpoint = (mgCfg.genEndpoint().isEmpty() ? config.endpoint : mgCfg.genEndpoint())
                        .replace("{model}", model);
                url = base + endpoint;
                body = buildVertexBody(parts);
            }
            case "MusicMinimax" -> {
                url = mgCfg.genEndpoint().startsWith("http") ? mgCfg.genEndpoint() : base + mgCfg.genEndpoint();
                body = buildMinimaxBody(parts, model);
            }
            default -> { // MusicGenerateContent (Gemini)
                String endpoint = mgCfg.genEndpoint().isEmpty() ? config.endpoint : mgCfg.genEndpoint();
                url = RequestBuilder.buildUrl(config, endpoint, apiKey, model, baseUrlOverride);
                body = buildGeminiBody(parts);
            }
        }

        HttpTransport.Result result = http.postJson(url, Json.serialize(body), headers);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw ResponseParser.parseError(config, result.statusCode(), result.body());
        }
        JsonElement raw = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        MusicResponse parsed = parseResponse(mgCfg.wireShape(), modelDef.outputMime(), raw);
        if (options.raw) {
            parsed = new MusicResponse(
                    parsed.audio(), parsed.text(), parsed.usage(), parsed.finishReason(), parsed.finishMessage(), raw);
        }
        return parsed;
    }

    // --- Request bodies ---

    /**
     * Vertex AI Lyria {@code :predict} body. Lyria 2 has no lyrics wire-slot,
     * so any lyrics parts fold into the prompt text (ADR-037 MUS-008); the
     * instrumental model ignores vocal content. instances/parameters
     * envelope mirrors Imagen.
     */
    private static JsonObject buildVertexBody(List<Part> parts) {
        String prompt = joinPromptText(parts);
        String lyrics = joinLyricsText(parts);
        if (!lyrics.isEmpty()) {
            prompt = prompt.isEmpty() ? lyrics : prompt + "\n" + lyrics;
        }

        JsonObject instance = new JsonObject();
        instance.addProperty("prompt", prompt);
        JsonArray instances = new JsonArray();
        instances.add(instance);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("sampleCount", 1);

        JsonObject body = new JsonObject();
        body.add("instances", instances);
        body.add("parameters", parameters);
        return body;
    }

    /**
     * Gemini {@code generateContent} body for Lyria 3. Text and lyrics parts
     * both serialize as {text} parts in caller order (Gemini takes custom
     * lyrics inline in the prompt text). {@code responseModalities} requests
     * AUDIO output.
     */
    private static JsonObject buildGeminiBody(List<Part> parts) {
        JsonArray wire = new JsonArray();
        for (Part part : parts) {
            JsonObject block = new JsonObject();
            if (part instanceof Part.Text text) {
                block.addProperty("text", text.text());
            } else if (part instanceof Part.Lyrics lyrics) {
                block.addProperty("text", lyrics.text());
            }
            wire.add(block);
        }
        JsonObject contentPart = new JsonObject();
        contentPart.add("parts", wire);
        JsonArray contents = new JsonArray();
        contents.add(contentPart);

        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        JsonObject generationConfig = new JsonObject();
        generationConfig.add("responseModalities", modalities);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);
        return body;
    }

    /**
     * MiniMax {@code /v1/music_generation} body. Prompt parts join into
     * {@code prompt}; lyrics parts join into {@code lyrics}.
     * {@code output_format=hex} returns hex-encoded audio at
     * {@code data.audio}.
     */
    private static JsonObject buildMinimaxBody(List<Part> parts, String model) {
        JsonObject audioSetting = new JsonObject();
        audioSetting.addProperty("sample_rate", 44100);
        audioSetting.addProperty("bitrate", 128000);
        audioSetting.addProperty("format", "mp3");

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", joinPromptText(parts));
        body.addProperty("output_format", "hex");
        body.add("audio_setting", audioSetting);
        String lyrics = joinLyricsText(parts);
        if (!lyrics.isEmpty()) {
            body.addProperty("lyrics", lyrics);
        }
        return body;
    }

    // --- Response parsing (selected by wireShape, never provider name) ---

    private static MusicResponse parseResponse(String wireShape, String fallbackMime, JsonElement raw) {
        return switch (wireShape) {
            case "MusicPredict" -> parseVertexResponse(raw, fallbackMime);
            case "MusicMinimax" -> parseMinimaxResponse(raw, fallbackMime);
            default -> parseGeminiResponse(raw, fallbackMime); // MusicGenerateContent
        };
    }

    /**
     * Vertex Lyria {@code :predict} responses. Shape:
     * {@code {"predictions": [{"audioContent": "<base64>", "mimeType": "audio/wav"}]}}.
     */
    private static MusicResponse parseVertexResponse(JsonElement raw, String fallbackMime) {
        List<AudioData> audio = new ArrayList<>();
        String finishReason = "";
        JsonElement predsElement = Json.at(raw, "predictions");
        if (predsElement != null && predsElement.isJsonArray()) {
            for (JsonElement entryElement : predsElement.getAsJsonArray()) {
                if (!entryElement.isJsonObject()) {
                    continue;
                }
                JsonObject entry = entryElement.getAsJsonObject();
                if (finishReason.isEmpty()) {
                    JsonElement raiElement = entry.get("raiFilteredReason");
                    if (raiElement != null && raiElement.isJsonPrimitive() && !raiElement.getAsString().isEmpty()) {
                        finishReason = raiElement.getAsString();
                    }
                }
                String b64 = "";
                JsonElement audioContent = entry.get("audioContent");
                if (audioContent != null && audioContent.isJsonPrimitive() && !audioContent.getAsString().isEmpty()) {
                    b64 = audioContent.getAsString();
                } else {
                    JsonElement bytesB64 = entry.get("bytesBase64Encoded");
                    if (bytesB64 != null && bytesB64.isJsonPrimitive() && !bytesB64.getAsString().isEmpty()) {
                        b64 = bytesB64.getAsString();
                    }
                }
                if (b64.isEmpty()) {
                    continue;
                }
                byte[] decoded = Base64.getDecoder().decode(b64);
                String mime = fallbackMime;
                JsonElement mimeElement = entry.get("mimeType");
                if (mimeElement != null && mimeElement.isJsonPrimitive() && !mimeElement.getAsString().isEmpty()) {
                    mime = mimeElement.getAsString();
                }
                audio.add(new AudioData(mime, decoded));
            }
        }
        return new MusicResponse(audio, "", Usage.zero(), finishReason, "", null);
    }

    /**
     * Gemini responses. Walks {@code candidates[0].content.parts}, decoding
     * each {@code inlineData} audio part and concatenating text parts
     * (generated lyrics).
     */
    private static MusicResponse parseGeminiResponse(JsonElement raw, String fallbackMime) {
        JsonElement candidatesElement = Json.at(raw, "candidates");
        if (candidatesElement == null
                || !candidatesElement.isJsonArray()
                || candidatesElement.getAsJsonArray().size() == 0) {
            return new MusicResponse(List.of(), "", Usage.zero(), "", "", null);
        }
        JsonElement firstElement = candidatesElement.getAsJsonArray().get(0);
        if (!firstElement.isJsonObject()) {
            return new MusicResponse(List.of(), "", Usage.zero(), "", "", null);
        }
        JsonObject first = firstElement.getAsJsonObject();
        String finishReason = Json.stringAt(first, "finishReason");

        List<AudioData> audio = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        JsonElement partsElement = Json.at(first, "content.parts");
        if (partsElement != null && partsElement.isJsonArray()) {
            for (JsonElement partElement : partsElement.getAsJsonArray()) {
                if (!partElement.isJsonObject()) {
                    continue;
                }
                JsonObject part = partElement.getAsJsonObject();
                JsonElement inlineElement = part.get("inlineData");
                if (inlineElement != null && inlineElement.isJsonObject()) {
                    JsonObject inline = inlineElement.getAsJsonObject();
                    JsonElement dataElement = inline.get("data");
                    if (dataElement != null && dataElement.isJsonPrimitive() && !dataElement.getAsString().isEmpty()) {
                        byte[] decoded = Base64.getDecoder().decode(dataElement.getAsString());
                        String mime = fallbackMime;
                        JsonElement mimeElement = inline.get("mimeType");
                        if (mimeElement != null && mimeElement.isJsonPrimitive() && !mimeElement.getAsString().isEmpty()) {
                            mime = mimeElement.getAsString();
                        }
                        audio.add(new AudioData(mime, decoded));
                    }
                }
                JsonElement textElement = part.get("text");
                if (textElement != null && textElement.isJsonPrimitive() && !textElement.getAsString().isEmpty()) {
                    text.append(textElement.getAsString());
                }
            }
        }
        return new MusicResponse(audio, text.toString(), Usage.zero(), finishReason, "", null);
    }

    /**
     * MiniMax responses. Shape:
     * {@code {"data": {"audio": "<hex>"}, "base_resp": {"status_msg": "..."}}}.
     */
    private static MusicResponse parseMinimaxResponse(JsonElement raw, String fallbackMime) {
        List<AudioData> audio = new ArrayList<>();
        String hex = Json.stringAt(raw, "data.audio");
        if (!hex.isEmpty()) {
            byte[] decoded = hexDecode(hex);
            if (decoded != null) {
                audio.add(new AudioData(fallbackMime, decoded));
            }
        }
        String finishMessage = "";
        String statusMsg = Json.stringAt(raw, "base_resp.status_msg");
        if (!statusMsg.isEmpty() && !"success".equals(statusMsg)) {
            finishMessage = statusMsg;
        }
        return new MusicResponse(audio, "", Usage.zero(), "", finishMessage, null);
    }

    /**
     * Decode a hex string to bytes. Returns null on odd length or any
     * non-hex digit (matching Go's {@code hex.DecodeString} error -> no
     * audio).
     */
    private static byte[] hexDecode(String hex) {
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static MusicModelDef findModel(MusicGenDef cfg, String modelId) {
        for (MusicModelDef m : cfg.models()) {
            if (m.modelId().equals(modelId)) {
                return m;
            }
        }
        return null;
    }
}
