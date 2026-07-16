package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;

/** Immutable, clone-on-chain builder for text generation. */
public final class Text {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    private final String system;
    private final Integer maxTokens;

    private Text(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            String system,
            Integer maxTokens) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.system = system;
        this.maxTokens = maxTokens;
    }

    static Text root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Text(provider, apiKey, baseUrlOverride, http, null, null, null);
    }

    /** Select the model. */
    public Text model(String model) {
        return new Text(provider, apiKey, baseUrlOverride, http, model, system, maxTokens);
    }

    /** Set the system instruction. */
    public Text system(String system) {
        return new Text(provider, apiKey, baseUrlOverride, http, model, system, maxTokens);
    }

    /** Set the maximum output tokens. */
    public Text maxTokens(int maxTokens) {
        return new Text(provider, apiKey, baseUrlOverride, http, model, system, maxTokens);
    }

    /** Send a single-turn prompt and return the response. */
    public Response prompt(String userPrompt) {
        Providers.Spec config = Providers.config(provider);
        String resolvedModel = resolveModel(config);
        int resolvedMaxTokens = maxTokens != null ? maxTokens : config.defaultMaxTokens;

        RequestBuilder.Built built = RequestBuilder.buildRequest(
                config, apiKey, resolvedModel, system, userPrompt, resolvedMaxTokens);
        String url = RequestBuilder.buildUrl(config, baseUrlOverride);

        HttpTransport.Result result =
                http.postJson(url, Json.serialize(built.body()), built.headers());
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw ResponseParser.parseError(config, result.statusCode(), result.body());
        }
        return ResponseParser.parse(config, result.body());
    }

    /**
     * The chosen model, or the provider default; throws when neither exists
     * (ADR-031 honest no-default contract).
     */
    private String resolveModel(Providers.Spec config) {
        if (model != null) {
            return model;
        }
        if (config.defaultModel.isEmpty()) {
            throw new ValidationException(
                    "model",
                    "no model chosen and \"" + config.slug + "\" declares no default");
        }
        return config.defaultModel;
    }
}
