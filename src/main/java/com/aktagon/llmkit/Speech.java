package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.AudioData;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.SpeechGenDef;
import com.aktagon.llmkit.providers.generated.SpeechModelDef;
import com.aktagon.llmkit.providers.generated.SpeechResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * ({@code audioResponseEncoding}) — never the provider name. Two shapes ship:
 * {@code SpeechInworld} (a flat-JSON POST whose reply carries base64 audio at
 * {@code audioContent}, ADR-049; Basic-prefixed auth carries the raw API key
 * VERBATIM, never re-encoded) and {@code SpeechOpenAI} (a flat-JSON POST
 * whose reply is RAW audio bytes — never JSON — ADR-051 slice 1). Pre-flight
 * validation (model + text + voice required; provider supports speech; model
 * + voice in the catalogue) runs before any HTTP call. No middleware (mirrors
 * the Rust / Swift runtime).
 */
public final class Speech {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    private final String voice;

    private Speech(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            String voice) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.voice = voice;
    }

    static Speech root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Speech(provider, apiKey, baseUrlOverride, http, null, null);
    }

    /** Select the speech-generation model (required). */
    public Speech model(String model) {
        return new Speech(provider, apiKey, baseUrlOverride, http, model, voice);
    }

    /**
     * Select the voice — request-data selector validated pre-flight against
     * the provider's catalogue (SPK-004).
     */
    public Speech voice(String voice) {
        return new Speech(provider, apiKey, baseUrlOverride, http, model, voice);
    }

    /**
     * Synthesize speech audio from {@code text}. Builds the provider body,
     * sends it once, and decodes the reply per the wire shape's audio
     * encoding.
     */
    public SpeechResponse generate(String text) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ValidationException("api_key", "required");
        }
        if (model == null || model.isEmpty()) {
            throw new ValidationException("model", "required for speech generation");
        }
        if (text == null || text.isEmpty()) {
            throw new ValidationException("text", "required for speech generation");
        }
        if (voice == null || voice.isEmpty()) {
            throw new ValidationException("voice", "required for speech generation");
        }

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
        if (!sgCfg.voices().contains(voice)) {
            throw new ValidationException("voice", voice + " is not a known voice for " + config.slug);
        }

        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        String endpoint = sgCfg.genEndpoint().isEmpty() ? config.endpoint : sgCfg.genEndpoint();
        String url = endpoint.startsWith("http") ? endpoint : base + endpoint;

        JsonObject body = "SpeechOpenAI".equals(sgCfg.wireShape())
                ? buildOpenAIBody(model, voice, text)
                : buildInworldBody(model, voice, text);

        // The OpenAI shape returns binary audio (not JSON), so the reply is
        // read as raw bytes and must not be lossily UTF-8 decoded before the
        // encoding fork.
        HttpTransport.Result result = http.postJson(url, Json.serialize(body), headers);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw ResponseParser.parseError(config, result.statusCode(), result.body());
        }
        return parseResponse(config.slug, sgCfg.audioResponseEncoding(), modelDef.outputMime(), result.body());
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
     * OpenAI {@code /v1/audio/speech} body. Slice 1 fixes
     * {@code response_format=mp3} (KISS); format selection is a later slice
     * (ADR-051).
     */
    private static JsonObject buildOpenAIBody(String model, String voice, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        body.addProperty("response_format", "mp3");
        return body;
    }

    // --- Response parsing (selected by audioResponseEncoding, never provider name) ---

    /**
     * Decode the synthesized audio per the wire shape's audio response
     * encoding (ADR-051 OAA-002). {@code rawBody} (OpenAI) takes the
     * response body verbatim as the audio bytes; {@code base64Envelope}
     * (Inworld) parses a JSON envelope and base64-decodes the
     * {@code audioContent} field. A 2xx body that does not parse to audio
     * is a {@link DecodingException} (HANDOFF-036 A5) — never a silent
     * empty clip.
     */
    private static SpeechResponse parseResponse(
            String providerSlug, String encoding, String fallbackMime, byte[] body) {
        byte[] bytes;
        if ("rawBody".equals(encoding)) {
            bytes = body;
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
        return new SpeechResponse(new AudioData(fallbackMime, bytes), Usage.zero(), "");
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
