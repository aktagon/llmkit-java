package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import java.util.List;







public final class Text {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    private final String system;
    private final PromptOptions options;

    private Text(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            String system,
            PromptOptions options) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.system = system;
        this.options = options;
    }

    static Text root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Text(provider, apiKey, baseUrlOverride, http, null, null, new PromptOptions());
    }


    public Text model(String model) {
        return new Text(provider, apiKey, baseUrlOverride, http, model, system, options);
    }


    public Text system(String system) {
        return new Text(provider, apiKey, baseUrlOverride, http, model, system, options);
    }


    public Text maxTokens(int maxTokens) {
        return withOptions(o -> o.maxTokens = maxTokens);
    }


    public Text temperature(double value) {
        return withOptions(o -> o.temperature = value);
    }


    public Text topP(double value) {
        return withOptions(o -> o.topP = value);
    }


    public Text topK(int value) {
        return withOptions(o -> o.topK = value);
    }


    public Text seed(long value) {
        return withOptions(o -> o.seed = value);
    }


    public Text frequencyPenalty(double value) {
        return withOptions(o -> o.frequencyPenalty = value);
    }


    public Text presencePenalty(double value) {
        return withOptions(o -> o.presencePenalty = value);
    }


    public Text thinkingBudget(int value) {
        return withOptions(o -> o.thinkingBudget = value);
    }


    public Text reasoningEffort(String value) {
        return withOptions(o -> o.reasoningEffort = value);
    }


    public Text stopSequences(List<String> values) {
        return withOptions(o -> o.stopSequences = List.copyOf(values));
    }


    public Text safetySettings(List<SafetySetting> values) {
        return withOptions(o -> o.safetySettings = List.copyOf(values));
    }


    public Text schema(String schema) {
        return withOptions(o -> o.schema = schema);
    }


    public Text protocol(String token) {
        return withOptions(o -> o.proto = token);
    }


    public Response prompt(String userPrompt) {
        Providers.Spec config = Providers.config(provider);
        RequestBuilder.Resolved resolved = RequestBuilder.resolveChatProtocol(config, options.proto);
        String resolvedModel = resolveModel(config);

        RequestBuilder.Built built = RequestBuilder.buildBody(
                config, resolved.wireShape(), apiKey, resolvedModel, system, userPrompt, options);
        String url = RequestBuilder.buildUrl(config, resolved.endpoint(), apiKey, resolvedModel, baseUrlOverride);

        HttpTransport.Result result =
                http.postJson(url, Json.serialize(built.body()), built.headers());
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw ResponseParser.parseError(config, result.statusCode(), result.body());
        }
        return ResponseParser.parse(config, result.body());
    }





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


    private Text withOptions(java.util.function.Consumer<PromptOptions> mutate) {
        PromptOptions copy = options.copy();
        mutate.accept(copy);
        return new Text(provider, apiKey, baseUrlOverride, http, model, system, copy);
    }
}
