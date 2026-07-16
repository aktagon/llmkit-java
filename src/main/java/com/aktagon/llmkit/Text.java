package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import java.util.List;

/**
 * Immutable, clone-on-chain builder for text generation. Phase 2 exposes the
 * full ChatCompletion configuration surface (options, structured output, the
 * Responses protocol) behind the non-streaming {@link #prompt} terminal; stream
 * / agent / batch execution modes land in Phase 3. Mirrors Swift's {@code Text}.
 */
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

    /** Select the model. */
    public Text model(String model) {
        return new Text(provider, apiKey, baseUrlOverride, http, model, system, options);
    }

    /** Set the system instruction. */
    public Text system(String system) {
        return new Text(provider, apiKey, baseUrlOverride, http, model, system, options);
    }

    /** Set the maximum output tokens. */
    public Text maxTokens(int maxTokens) {
        return withOptions(o -> o.maxTokens = maxTokens);
    }

    /** Sampling temperature. */
    public Text temperature(double value) {
        return withOptions(o -> o.temperature = value);
    }

    /** Nucleus-sampling probability mass. */
    public Text topP(double value) {
        return withOptions(o -> o.topP = value);
    }

    /** Top-k sampling cutoff. */
    public Text topK(int value) {
        return withOptions(o -> o.topK = value);
    }

    /** Deterministic sampling seed. */
    public Text seed(long value) {
        return withOptions(o -> o.seed = value);
    }

    /** Frequency penalty. */
    public Text frequencyPenalty(double value) {
        return withOptions(o -> o.frequencyPenalty = value);
    }

    /** Presence penalty. */
    public Text presencePenalty(double value) {
        return withOptions(o -> o.presencePenalty = value);
    }

    /** Extended-thinking token budget (Anthropic / Google). */
    public Text thinkingBudget(int value) {
        return withOptions(o -> o.thinkingBudget = value);
    }

    /** Reasoning-effort level (provider-validated whitelist). */
    public Text reasoningEffort(String value) {
        return withOptions(o -> o.reasoningEffort = value);
    }

    /** Stop sequences. */
    public Text stopSequences(List<String> values) {
        return withOptions(o -> o.stopSequences = List.copyOf(values));
    }

    /** Google safety settings. */
    public Text safetySettings(List<SafetySetting> values) {
        return withOptions(o -> o.safetySettings = List.copyOf(values));
    }

    /** Structured-output JSON Schema (as a JSON string). */
    public Text schema(String schema) {
        return withOptions(o -> o.schema = schema);
    }

    /** Chat-protocol opt-in (ADR-055), e.g. {@code "responses"}. */
    public Text protocol(String token) {
        return withOptions(o -> o.proto = token);
    }

    /** Send a single-turn prompt and return the response. */
    public Response prompt(String userPrompt) {
        Providers.Spec config = Providers.config(provider);
        RequestBuilder.Resolved resolved = RequestBuilder.resolveChatProtocol(config, options.proto);
        String resolvedModel = resolveModel(config);

        RequestBuilder.Built built = RequestBuilder.buildBody(
                config, resolved.wireShape(), apiKey, resolvedModel, system,
                List.of(new Msg.Text("user", userPrompt)), List.of(), options);
        String url = RequestBuilder.buildUrl(config, resolved.endpoint(), apiKey, resolvedModel, baseUrlOverride);

        HttpTransport.Result result =
                RequestBuilder.send(config, url, built.body(), built.headers(), apiKey, http);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw ResponseParser.parseError(config, result.statusCode(), result.body());
        }
        return ResponseParser.parse(config, result.body());
    }

    /**
     * Send a single-turn prompt over SSE, invoking {@code onDelta} per text
     * chunk, and return the assembled response (streaming execution mode;
     * BUG-028 usage opt-in applied where the provider requires it).
     */
    public Response stream(String userPrompt, java.util.function.Consumer<String> onDelta) {
        Providers.Spec config = Providers.config(provider);
        String resolvedModel = resolveModel(config);
        return Streaming.run(
                config, apiKey, resolvedModel, system,
                List.of(new Msg.Text("user", userPrompt)), options, http, baseUrlOverride, onDelta);
    }

    /**
     * Submit the prompts as an async batch and return the live handle
     * (ADR-064 batch-as-text-execution-mode). Blocking one-liner:
     * {@code batch(...).await()}.
     */
    public BatchJob batch(String... prompts) {
        Providers.Spec config = Providers.config(provider);
        String resolvedModel = resolveModel(config);
        return Batching.submit(
                config, apiKey, http, baseUrlOverride, resolvedModel, system,
                java.util.Arrays.asList(prompts), options);
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

    /** Clone-on-chain: copy the options, mutate, return a fresh builder. */
    private Text withOptions(java.util.function.Consumer<PromptOptions> mutate) {
        PromptOptions copy = options.copy();
        mutate.accept(copy);
        return new Text(provider, apiKey, baseUrlOverride, http, model, system, copy);
    }
}
