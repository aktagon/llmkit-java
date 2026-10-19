package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.AudioData;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.SpeechGenDef;
import com.aktagon.llmkit.providers.generated.SpeechModelDef;
import com.aktagon.llmkit.providers.generated.SpeechResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Immutable, clone-on-chain builder for text-to-speech generation (ADR-068
 * Phase 4d) — a port of Swift's {@code Speech} / Rust's {@code speech.rs}.
 * Synchronous: {@code client.speech().<config>.generate(text)} builds the
 * provider request body, sends it once, and parses the reply into the
 * universal {@link SpeechResponse}.
 *
 * <p>The generated {@code speechGenConfig(provider)} fact selects both the
 * request body ({@code wireShape}) and the audio decode
 * ({@code audioResponseEncoding}) — never the provider name. Five shapes ship:
 * {@code SpeechInworld} (a flat-JSON POST whose reply carries base64 audio at
 * {@code audioContent}, ADR-049; Basic-prefixed auth carries the raw API key
 * VERBATIM, never re-encoded) and {@code SpeechOpenAI} (a flat-JSON POST
 * whose reply is RAW audio bytes — never JSON — ADR-051 slice 1), plus
 * {@code SpeechGemini} (Interactions API, base64 audio in {@code steps[]})
 * {@code SpeechMiniMax} (hex audio at {@code data.audio}) and
 * {@code SpeechXAI} (a flat-JSON POST whose reply is RAW audio bytes). Pre-flight
 * validation (model + text or lines required; voice or speakers; provider
 * supports speech; model known; speakers and style the model takes, BUG-091)
 * runs before any HTTP call. Voices are free-form: there is no catalogue.
 * Fires the {@code SPEECH_GENERATION} middleware op around the request
 * (BUG-036).
 */
public final class Speech {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    private final String voice;
    private final String style;
    private final List<Speaker> speakers;
    private final List<Line> lines;
    /** Observation + veto hooks fired around the {@code SPEECH_GENERATION} op. */
    private final List<MiddlewareFn> middleware;

    /** One named speaker of a multi-speaker request and its voice (BUG-091). */
    private record Speaker(String name, String voice) {}

    /** One line of a multi-speaker request; style "" means none (BUG-091). */
    private record Line(String speaker, String style, String text) {}

    private Speech(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            String voice,
            String style,
            List<Speaker> speakers,
            List<Line> lines,
            List<MiddlewareFn> middleware) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.voice = voice;
        this.style = style;
        this.speakers = speakers;
        this.lines = lines;
        this.middleware = middleware;
    }

    static Speech root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Speech(provider, apiKey, baseUrlOverride, http, null, null, "", List.of(), List.of(), List.of());
    }

    /** Select the speech-generation model (required). */
    public Speech model(String model) {
        return new Speech(provider, apiKey, baseUrlOverride, http, model, voice, style, speakers, lines, middleware);
    }

    /**
     * Select the voice — a free-form voice id, sent as given (SPK-004; no
     * catalogue since 2026-10-15).
     */
    public Speech voice(String voice) {
        return new Speech(provider, apiKey, baseUrlOverride, http, model, voice, style, speakers, lines, middleware);
    }

    /**
     * Sets the delivery style for a single-voice speech request, such as
     * "cheerful". A model that does not take a style rejects it before send.
     */
    public Speech style(String style) {
        return new Speech(provider, apiKey, baseUrlOverride, http, model, voice, style, speakers, lines, middleware);
    }

    /**
     * Declares a named speaker and its voice for a multi-speaker speech
     * request. Declare exactly 2 speakers, and do not set a voice with them.
     */
    public Speech addSpeaker(String name, String voice) {
        List<Speaker> next = new ArrayList<>(speakers);
        next.add(new Speaker(name, voice));
        return new Speech(
                provider, apiKey, baseUrlOverride, http, model, this.voice, style, List.copyOf(next), lines, middleware);
    }

    /**
     * Appends a line said by a declared speaker. Lines are spoken in call
     * order. When lines are set, the text passed to generate must be empty.
     */
    public Speech addLine(String speaker, String text) {
        return addStyledLine(speaker, "", text);
    }

    /**
     * Appends a line said by a declared speaker in a delivery style, such as
     * "calm and relaxed". Otherwise it works like addLine.
     */
    public Speech addStyledLine(String speaker, String style, String text) {
        List<Line> next = new ArrayList<>(lines);
        next.add(new Line(speaker, style, text));
        return new Speech(
                provider, apiKey, baseUrlOverride, http, model, voice, this.style, speakers, List.copyOf(next), middleware);
    }

    /** Register a middleware hook (observation + pre-phase veto). */
    public Speech addMiddleware(MiddlewareFn hook) {
        List<MiddlewareFn> hooks = new ArrayList<>(middleware);
        hooks.add(hook);
        return new Speech(
                provider, apiKey, baseUrlOverride, http, model, voice, style, speakers, lines, List.copyOf(hooks));
    }

    /**
     * Synthesize speech audio from {@code text}. Builds the provider body,
     * sends it once, and decodes the reply per the wire shape's audio
     * encoding. Fires the {@code SPEECH_GENERATION} middleware op pre + post
     * around the request (pre-phase veto, post-phase observation).
     */
    public SpeechResponse generate(String text) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ValidationException("api_key", "required");
        }
        if (model == null || model.isEmpty()) {
            throw new ValidationException("model", "required for speech generation");
        }
        String spoken = text == null ? "" : text;
        String voiceId = voice == null ? "" : voice;
        validateInputs(voiceId, spoken);

        Providers.Spec config = Providers.config(provider);
        SpeechGenDef sgCfg = SpeechGenDef.speechGenConfig(provider);
        if (sgCfg == null) {
            throw new ValidationException("provider", config.slug + " does not support speech generation");
        }
        SpeechModelDef modelDef = findModel(sgCfg, model);
        if (modelDef == null) {
            throw new ValidationException(
                    "model", model + " is not a known speech-generation model for " + config.slug);
        }
        validateModel(sgCfg, modelDef);

        Event baseEvent = Event.of(MiddlewareOp.SPEECH_GENERATION, config.slug, model);
        long startNanos = System.nanoTime();
        Middleware.firePre(middleware, baseEvent);

        try {
            Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

            String endpoint = sgCfg.genEndpoint().isEmpty() ? config.endpoint : sgCfg.genEndpoint();
            // buildUrl appends the QueryParamKey ?key= for Google, as on every
            // other Google call. Speech base: explicit override > the config's
            // distinct speech base (MiniMax, HANDOFF-094) > chat base.
            String speechBase = baseUrlOverride != null
                    ? baseUrlOverride
                    : (sgCfg.baseUrl().isEmpty() ? null : sgCfg.baseUrl());
            String url = endpoint.startsWith("http")
                    ? endpoint
                    : RequestBuilder.buildUrl(config, endpoint, apiKey, model, speechBase);

            JsonObject body = switch (sgCfg.wireShape()) {
                case "SpeechOpenAI" -> buildOpenAIBody(model, voiceId, spoken, style);
                case "SpeechGemini" -> buildGeminiBody(model, voiceId, spoken);
                case "SpeechMiniMax" -> buildMiniMaxBody(model, voiceId, spoken);
                case "SpeechXAI" -> buildXAIBody(voiceId, spoken);
                default -> buildInworldBody(model, voiceId, spoken);
            };

            // The OpenAI shape returns binary audio (not JSON), so the reply is
            // read as raw bytes and must not be lossily UTF-8 decoded before the
            // encoding fork.
            HttpTransport.Result result = http.postJson(url, Json.serialize(body), headers);
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw ResponseCodec.parseError(config, result.statusCode(), result.body());
            }
            SpeechResponse response =
                    parseResponse(config.slug, sgCfg.audioResponseEncoding(), modelDef.outputMime(), result.body());
            Middleware.firePost(
                    middleware,
                    baseEvent.toPost("", response.usage(), null, Middleware.elapsedMillis(startNanos)));
            return response;
        } catch (RuntimeException e) {
            Middleware.firePost(
                    middleware, baseEvent.toPost("", null, e, Middleware.elapsedMillis(startNanos)));
            throw e;
        }
    }

    /**
     * Checks the request on its own, before the provider config is read
     * (BUG-091). The messages are the same in every SDK.
     */
    private void validateInputs(String voiceId, String text) {
        if (lines.isEmpty() && text.isEmpty()) {
            throw new ValidationException("text", "required for speech generation");
        }
        if (!lines.isEmpty() && !text.isEmpty()) {
            throw new ValidationException("text", "must be empty when lines are set");
        }
        if (speakers.isEmpty()) {
            if (voiceId.isEmpty()) {
                throw new ValidationException("voice", "required for speech generation");
            }
        } else {
            if (!voiceId.isEmpty()) {
                throw new ValidationException("voice", "cannot be set together with speakers");
            }
            if (!style.isEmpty()) {
                throw new ValidationException("style", "cannot be set together with speakers; style each line instead");
            }
            if (lines.isEmpty()) {
                throw new ValidationException("lines", "required when speakers are set");
            }
        }
        for (Line line : lines) {
            if (speakers.stream().noneMatch(s -> s.name().equals(line.speaker()))) {
                throw new ValidationException("lines", line.speaker() + " is not a declared speaker");
            }
        }
    }

    /**
     * Checks the request against what the config's body can carry and the
     * model's stated speaker and style facts (BUG-091). An unknown model fact
     * (null) sends the request; only a stated false or a stated speaker count
     * that does not match blocks it (decision 009, HANDOFF-099). Package-private
     * so the tests can reach it.
     */
    void validateModel(SpeechGenDef sgCfg, SpeechModelDef modelDef) {
        Integer max = modelDef.maxSpeakers();
        if (!speakers.isEmpty()) {
            if (!sgCfg.multiSpeaker() || Integer.valueOf(1).equals(max)) {
                throw new ValidationException("speakers", modelDef.modelId() + " does not support multiple speakers");
            }
            if (max != null && speakers.size() != max) {
                throw new ValidationException(
                        "speakers", "exactly " + max + " speakers are required, got " + speakers.size());
            }
        }
        boolean styled = !style.isEmpty() || lines.stream().anyMatch(l -> !l.style().isEmpty());
        if (styled && (!sgCfg.carriesSpeechStyle() || Boolean.FALSE.equals(modelDef.supportsSpeechStyle()))) {
            throw new ValidationException("style", modelDef.modelId() + " does not support a speech style");
        }
    }

    // --- Request bodies ---

    /**
     * Inworld {@code /tts/v1/voice} body. Slice 1 sends a fixed audioConfig
     * (LINEAR16/22050 -> WAV) and BALANCED delivery; format/sample-rate
     * selection is a later slice (ADR-049 OQ-5).
     */
    private static JsonObject buildInworldBody(String model, String voice, String text) {
        JsonObject audioConfig = new JsonObject();
        audioConfig.addProperty("audioEncoding", "LINEAR16");
        audioConfig.addProperty("sampleRateHertz", 22050);

        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("voiceId", voice);
        body.addProperty("modelId", model);
        body.add("audioConfig", audioConfig);
        body.addProperty("deliveryMode", "BALANCED");
        return body;
    }

    /**
     * xAI {@code /v1/tts} body (HANDOFF-095). The endpoint has no model
     * field. language is always "auto" (llmkit has no language option), and
     * with_timestamps is never sent: it switches the reply from raw audio
     * bytes to JSON.
     */
    private static JsonObject buildXAIBody(String voice, String text) {
        JsonObject outputFormat = new JsonObject();
        outputFormat.addProperty("codec", "mp3");
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("voice_id", voice);
        body.addProperty("language", "auto");
        body.add("output_format", outputFormat);
        return body;
    }

    /**
     * MiniMax {@code /v1/t2a_v2} body (HANDOFF-094): only the fields the
     * reference marks required. stream, output_format (hex) and
     * audio_setting (mp3) take their defaults.
     */
    private static JsonObject buildMiniMaxBody(String model, String voice, String text) {
        JsonObject voiceSetting = new JsonObject();
        voiceSetting.addProperty("voice_id", voice);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("text", text);
        body.add("voice_setting", voiceSetting);
        return body;
    }

    /**
     * OpenAI {@code /v1/audio/speech} body. Slice 1 fixes
     * {@code response_format=mp3} (KISS); format selection is a later slice
     * (ADR-051). A style is sent as {@code instructions} (BUG-091), which
     * gpt-4o-mini-tts accepts.
     */
    private static JsonObject buildOpenAIBody(String model, String voice, String text, String style) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        body.addProperty("response_format", "mp3");
        if (!style.isEmpty()) {
            body.addProperty("instructions", style);
        }
        return body;
    }

    /**
     * Gemini TTS body on the Interactions API (BUG-091): one user_input turn
     * and an audio response. Single voice: one text item and
     * {@code speech_config [{voice}]}; a style annotates the text item.
     * Multi-speaker: one text item per line, each annotated with its speaker
     * and optional style, and {@code speech_config {speakers}}. {@code store}
     * is always false: the Interactions API keeps requests by default.
     */
    private JsonObject buildGeminiBody(String model, String voice, String text) {
        JsonArray content = new JsonArray();
        JsonElement speechConfigValue;
        if (speakers.isEmpty()) {
            JsonObject textItem = new JsonObject();
            textItem.addProperty("type", "text");
            textItem.addProperty("text", text);
            if (!style.isEmpty()) {
                JsonObject meta = new JsonObject();
                meta.addProperty("type", "speech_metadata");
                meta.addProperty("style", style);
                JsonArray annotations = new JsonArray();
                annotations.add(meta);
                textItem.add("annotations", annotations);
            }
            content.add(textItem);
            JsonObject speaker = new JsonObject();
            speaker.addProperty("voice", voice);
            JsonArray voices = new JsonArray();
            voices.add(speaker);
            speechConfigValue = voices;
        } else {
            for (Line line : lines) {
                JsonObject meta = new JsonObject();
                meta.addProperty("type", "speech_metadata");
                meta.addProperty("speaker", line.speaker());
                if (!line.style().isEmpty()) {
                    meta.addProperty("style", line.style());
                }
                JsonArray annotations = new JsonArray();
                annotations.add(meta);
                JsonObject item = new JsonObject();
                item.addProperty("type", "text");
                item.addProperty("text", line.text());
                item.add("annotations", annotations);
                content.add(item);
            }
            JsonArray speakerArray = new JsonArray();
            for (Speaker s : speakers) {
                JsonObject entry = new JsonObject();
                entry.addProperty("speaker", s.name());
                entry.addProperty("voice", s.voice());
                speakerArray.add(entry);
            }
            JsonObject multi = new JsonObject();
            multi.add("speakers", speakerArray);
            speechConfigValue = multi;
        }
        JsonObject turn = new JsonObject();
        turn.addProperty("type", "user_input");
        turn.add("content", content);
        JsonArray input = new JsonArray();
        input.add(turn);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "audio");

        JsonObject generationConfig = new JsonObject();
        generationConfig.add("speech_config", speechConfigValue);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("input", input);
        body.add("response_format", responseFormat);
        body.add("generation_config", generationConfig);
        body.addProperty("store", false);
        return body;
    }

    // --- Response parsing (selected by audioResponseEncoding, never provider name) ---

    /**
     * Decode the synthesized audio per the wire shape's audio response
     * encoding (ADR-051 OAA-002). {@code rawBody} (OpenAI, xAI) takes the
     * response body verbatim as the audio bytes; {@code base64Envelope}
     * (Inworld) parses a JSON envelope and base64-decodes the
     * {@code audioContent} field; {@code hexEnvelope} (MiniMax) hex-decodes
     * {@code data.audio} and raises on a nonzero {@code base_resp}. A 2xx
     * body that does not parse to audio
     * is a {@link DecodingException} (HANDOFF-036 A5) — never a silent
     * empty clip.
     */
    private static SpeechResponse parseResponse(
            String providerSlug, String encoding, String fallbackMime, byte[] body) {
        byte[] bytes;
        if ("rawBody".equals(encoding)) {
            bytes = body;
        } else if ("base64InteractionSteps".equals(encoding)) {
            return parseInteractionSteps(providerSlug, fallbackMime, body);
        } else if ("hexEnvelope".equals(encoding)) {
            bytes = parseHexEnvelope(providerSlug, body);
        } else {
            String b64;
            try {
                JsonElement raw = Json.parse(new String(body, StandardCharsets.UTF_8));
                b64 = Json.stringAt(raw, "audioContent");
            } catch (DecodingException e) {
                throw new DecodingException(
                        providerSlug + " speech response: not valid JSON: " + e.getMessage(), e);
            }
            if (b64.isEmpty()) {
                throw new DecodingException(providerSlug + " speech response: missing or empty audioContent");
            }
            try {
                bytes = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new DecodingException(
                        providerSlug + " speech response: invalid base64 in audioContent: " + e.getMessage(), e);
            }
        }
        return new SpeechResponse(new AudioData(fallbackMime, bytes), Usage.none(), null);
    }

    /**
     * Decode a Gemini TTS Interactions API reply (BUG-091). Google's
     * documented path is
     * {@code [.steps[] | select(.type=="model_output") | .content[] | select(.type=="audio")] | last | .data}:
     * the LAST audio item across the model_output steps wins. Its
     * {@code mime_type} is used when present, else the model's outputMime.
     * No audio item, or invalid base64, is a {@link DecodingException}
     * (HANDOFF-036 A5).
     */
    private static SpeechResponse parseInteractionSteps(String providerSlug, String fallbackMime, byte[] body) {
        JsonElement raw;
        try {
            raw = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (DecodingException e) {
            throw new DecodingException(providerSlug + " speech response: not valid JSON: " + e.getMessage(), e);
        }
        JsonObject last = null;
        JsonElement steps = raw.isJsonObject() ? raw.getAsJsonObject().get("steps") : null;
        if (steps != null && steps.isJsonArray()) {
            for (JsonElement step : steps.getAsJsonArray()) {
                if (!"model_output".equals(Json.stringAt(step, "type"))) {
                    continue;
                }
                JsonElement content = step.getAsJsonObject().get("content");
                if (content == null || !content.isJsonArray()) {
                    continue;
                }
                for (JsonElement item : content.getAsJsonArray()) {
                    if ("audio".equals(Json.stringAt(item, "type"))) {
                        last = item.getAsJsonObject();
                    }
                }
            }
        }
        String b64 = last == null ? "" : Json.stringAt(last, "data");
        if (b64.isEmpty()) {
            throw new DecodingException(
                    providerSlug + " speech response: no audio item in steps[model_output].content");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new DecodingException(
                    providerSlug + " speech response: invalid base64 in audio data: " + e.getMessage(), e);
        }
        String mime = Json.stringAt(last, "mime_type");
        return new SpeechResponse(
                new AudioData(mime.isEmpty() ? fallbackMime : mime, bytes), Usage.none(), null);
    }

    /**
     * Decode a MiniMax t2a_v2 reply (HANDOFF-094):
     * {@code {"data": {"audio": "<hex>"}, "base_resp": {"status_code", "status_msg"}}}.
     * MiniMax reports failures in {@code base_resp} on an HTTP 200, so a
     * nonzero {@code status_code} is an {@link ApiException} carrying
     * {@code status_msg}. A success with missing audio, or invalid hex, is a
     * {@link DecodingException} (HANDOFF-036 A5).
     */
    private static byte[] parseHexEnvelope(String providerSlug, byte[] body) {
        JsonElement raw;
        try {
            raw = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (DecodingException e) {
            throw new DecodingException(providerSlug + " speech response: not valid JSON: " + e.getMessage(), e);
        }
        long code = Json.longAt(raw, "base_resp.status_code");
        if (code != 0) {
            throw new ApiException(
                    providerSlug, 200,
                    Json.stringAt(raw, "base_resp.status_msg") + " (base_resp.status_code " + code + ")");
        }
        String hex = Json.stringAt(raw, "data.audio");
        if (hex.isEmpty()) {
            throw new DecodingException(providerSlug + " speech response: missing or empty data.audio");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new DecodingException(
                    providerSlug + " speech response: invalid hex in data.audio: " + e.getMessage(), e);
        }
    }

    private static SpeechModelDef findModel(SpeechGenDef cfg, String modelId) {
        for (SpeechModelDef m : cfg.models()) {
            if (m.modelId().equals(modelId)) {
                return m;
            }
        }
        return null;
    }
}
