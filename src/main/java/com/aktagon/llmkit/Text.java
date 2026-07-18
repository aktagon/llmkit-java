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
    private final List<InputImage> inputImages;
    private final List<FileRef> inputFiles;

    private Text(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            String system,
            PromptOptions options,
            List<InputImage> inputImages,
            List<FileRef> inputFiles) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.system = system;
        this.options = options;
        this.inputImages = inputImages;
        this.inputFiles = inputFiles;
    }

    static Text root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Text(
                provider, apiKey, baseUrlOverride, http, null, null, new PromptOptions(), List.of(), List.of());
    }

    /** Select the model. */
    public Text model(String model) {
        return new Text(
                provider, apiKey, baseUrlOverride, http, model, system, options, inputImages, inputFiles);
    }

    /** Set the system instruction. */
    public Text system(String system) {
        return new Text(
                provider, apiKey, baseUrlOverride, http, model, system, options, inputImages, inputFiles);
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

    /** Opt into prompt caching (ADR-026). */
    public Text caching() {
        return withOptions(o -> o.caching = true);
    }

    /** Set the cache TTL in seconds (resource caching only). */
    public Text cacheTtl(int seconds) {
        return withOptions(o -> o.cacheTtl = seconds);
    }

    /** Register a middleware hook (observation + pre-phase veto). */
    public Text addMiddleware(MiddlewareFn hook) {
        return withOptions(o -> o.middleware.add(hook));
    }

    /**
     * Attach an image as vision input to the prompt (ADR-060). The bytes are
     * lowered into a base64 data URI and emitted as the provider's native
     * image block. Multiple {@code .image(...)} calls accumulate in order.
     */
    public Text image(String mimeType, byte[] data) {
        List<InputImage> images = new java.util.ArrayList<>(inputImages);
        images.add(new InputImage(
                "data:" + mimeType + ";base64," + java.util.Base64.getEncoder().encodeToString(data),
                mimeType, ""));
        return new Text(
                provider, apiKey, baseUrlOverride, http, model, system, options,
                List.copyOf(images), inputFiles);
    }

    /**
     * Attach an uploaded-file reference to the prompt (ADR-060), emitted as
     * the provider's native document/file block. Multiple {@code .file(...)}
     * calls accumulate in order.
     */
    public Text file(String id) {
        List<FileRef> files = new java.util.ArrayList<>(inputFiles);
        files.add(new FileRef(id, "", ""));
        return new Text(
                provider, apiKey, baseUrlOverride, http, model, system, options,
                inputImages, List.copyOf(files));
    }

    /**
     * The internal user turn: a plain text turn, or a media turn carrying the
     * accumulated image/file parts (ADR-060). Files precede images precede
     * text in the emitted content array.
     */
    private List<Msg> userMsgs(String prompt) {
        if (inputImages.isEmpty() && inputFiles.isEmpty()) {
            return List.of(new Msg.Text("user", prompt));
        }
        return List.of(new Msg.Media("user", prompt, inputImages, inputFiles));
    }

    /**
     * Send a single-turn prompt and return the response. Fires the
     * {@code llmRequest} middleware op (pre-phase veto, post-phase
     * observation with usage) and applies prompt caching to the built body
     * when {@link #caching()} was set.
     */
    public Response prompt(String userPrompt) {
        Providers.Spec config = Providers.config(provider);
        RequestBuilder.Resolved resolved = RequestBuilder.resolveChatProtocol(config, options.proto);
        String resolvedModel = resolveModel(config);

        Event baseEvent = Event.of(MiddlewareOp.LLM_REQUEST, config.slug, resolvedModel);
        long startNanos = System.nanoTime();
        Middleware.firePre(options.middleware, baseEvent);

        try {
            RequestBuilder.Built built = RequestBuilder.buildBody(
                    config, resolved.wireShape(), apiKey, resolvedModel, system,
                    userMsgs(userPrompt), List.of(), options);
            CachingRuntime.apply(built.body(), config, resolvedModel, apiKey, options, http, baseUrlOverride);
            String url = RequestBuilder.buildUrl(config, resolved.endpoint(), apiKey, resolvedModel, baseUrlOverride);

            HttpTransport.Result result =
                    RequestBuilder.send(config, url, built.body(), built.headers(), apiKey, http);
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw ResponseParser.parseError(config, result.statusCode(), result.body());
            }
            Response response = ResponseParser.parse(config, result.body());
            Middleware.firePost(
                    options.middleware,
                    baseEvent.toPost("", response.usage(), null, Middleware.elapsedMillis(startNanos)));
            return response;
        } catch (RuntimeException e) {
            Middleware.firePost(
                    options.middleware,
                    baseEvent.toPost("", null, e, Middleware.elapsedMillis(startNanos)));
            throw e;
        }
    }

    /**
     * Send a single-turn prompt over SSE, invoking {@code onDelta} per text
     * chunk, and return the assembled response (streaming execution mode;
     * BUG-028 usage opt-in applied where the provider requires it).
     */
    public Response stream(String userPrompt, java.util.function.Consumer<String> onDelta) {
        rejectNonDefaultProtocol("stream");
        Providers.Spec config = Providers.config(provider);
        String resolvedModel = resolveModel(config);
        return Streaming.run(
                config, apiKey, resolvedModel, system,
                userMsgs(userPrompt), options, http, baseUrlOverride, onDelta);
    }

    /**
     * Submit the prompts as an async batch and return the live handle
     * (ADR-064 batch-as-text-execution-mode). Blocking one-liner:
     * {@code batch(...).await()}. Fires the {@code batchSubmit} middleware
     * op and threads caching into each per-item body (BUG-004).
     */
    public BatchJob batch(String... prompts) {
        rejectNonDefaultProtocol("batch");
        Providers.Spec config = Providers.config(provider);
        String resolvedModel = resolveModel(config);

        Event baseEvent = Event.of(MiddlewareOp.BATCH_SUBMIT, config.slug, resolvedModel);
        long startNanos = System.nanoTime();
        Middleware.firePre(options.middleware, baseEvent);

        try {
            BatchJob job = Batching.submit(
                    config, apiKey, http, baseUrlOverride, resolvedModel, system,
                    java.util.Arrays.asList(prompts), inputImages, inputFiles, options);
            Middleware.firePost(
                    options.middleware, baseEvent.toPost("", null, null, Middleware.elapsedMillis(startNanos)));
            return job;
        } catch (RuntimeException e) {
            Middleware.firePost(
                    options.middleware,
                    baseEvent.toPost("", null, e, Middleware.elapsedMillis(startNanos)));
            throw e;
        }
    }

    /**
     * The chosen model, or the provider default; throws when neither exists
     * (ADR-031 honest no-default contract).
     */
    /**
     * Enforce that a chat-protocol opt-in (ADR-055, e.g. Responses) is only
     * honored on the sync prompt terminal: the stream and batch execution
     * modes raise loudly rather than silently sending the default Chat
     * Completions envelope the caller explicitly opted out of. Mirrors Go's
     * {@code rejectNonDefaultProtocol}; uniform across the six SDKs.
     */
    private void rejectNonDefaultProtocol(String terminal) {
        if (options.proto == null || options.proto.isEmpty()) {
            return;
        }
        throw new ValidationException(
                "protocol",
                "protocol (e.g. Responses) is only supported on the prompt terminal, not " + terminal + " (ADR-055)");
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

    /** Clone-on-chain: copy the options, mutate, return a fresh builder. */
    private Text withOptions(java.util.function.Consumer<PromptOptions> mutate) {
        PromptOptions copy = options.copy();
        mutate.accept(copy);
        return new Text(
                provider, apiKey, baseUrlOverride, http, model, system, copy, inputImages, inputFiles);
    }
}
