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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;



















public final class Speech {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    private final String voice;

    private final List<MiddlewareFn> middleware;

    private Speech(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            String voice,
            List<MiddlewareFn> middleware) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.voice = voice;
        this.middleware = middleware;
    }

    static Speech root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Speech(provider, apiKey, baseUrlOverride, http, null, null, List.of());
    }


    public Speech model(String model) {
        return new Speech(provider, apiKey, baseUrlOverride, http, model, voice, middleware);
    }





    public Speech voice(String voice) {
        return new Speech(provider, apiKey, baseUrlOverride, http, model, voice, middleware);
    }


    public Speech addMiddleware(MiddlewareFn hook) {
        List<MiddlewareFn> hooks = new ArrayList<>(middleware);
        hooks.add(hook);
        return new Speech(provider, apiKey, baseUrlOverride, http, model, voice, List.copyOf(hooks));
    }







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

        Event baseEvent = Event.of(MiddlewareOp.SPEECH_GENERATION, config.slug, model);
        long startNanos = System.nanoTime();
        Middleware.firePre(middleware, baseEvent);

        try {
            Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

            String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
            String endpoint = sgCfg.genEndpoint().isEmpty() ? config.endpoint : sgCfg.genEndpoint();
            String url = endpoint.startsWith("http") ? endpoint : base + endpoint;

            JsonObject body = "SpeechOpenAI".equals(sgCfg.wireShape())
                    ? buildOpenAIBody(model, voice, text)
                    : buildInworldBody(model, voice, text);

            //
            //
            //
            HttpTransport.Result result = http.postJson(url, Json.serialize(body), headers);
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw ResponseParser.parseError(config, result.statusCode(), result.body());
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

    //






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






    private static JsonObject buildOpenAIBody(String model, String voice, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        body.addProperty("response_format", "mp3");
        return body;
    }

    //










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
