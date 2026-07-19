package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import java.util.List;

/*




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

    /**/
    public Text model(String model) {
        return new Text(
                provider, apiKey, baseUrlOverride, http, model, system, options, inputImages, inputFiles);
    }

    /**/
    public Text system(String system) {
        return new Text(
                provider, apiKey, baseUrlOverride, http, model, system, options, inputImages, inputFiles);
    }

    /**/
    public Text maxTokens(int maxTokens) {
        return withOptions(o -> o.maxTokens = maxTokens);
    }

    /**/
    public Text temperature(double value) {
        return withOptions(o -> o.temperature = value);
    }

    /**/
    public Text topP(double value) {
        return withOptions(o -> o.topP = value);
    }

    /**/
    public Text topK(int value) {
        return withOptions(o -> o.topK = value);
    }

    /**/
    public Text seed(long value) {
        return withOptions(o -> o.seed = value);
    }

    /**/
    public Text frequencyPenalty(double value) {
        return withOptions(o -> o.frequencyPenalty = value);
    }

    /**/
    public Text presencePenalty(double value) {
        return withOptions(o -> o.presencePenalty = value);
    }

    /**/
    public Text thinkingBudget(int value) {
        return withOptions(o -> o.thinkingBudget = value);
    }

    /**/
    public Text reasoningEffort(String value) {
        return withOptions(o -> o.reasoningEffort = value);
    }

    /**/
    public Text stopSequences(List<String> values) {
        return withOptions(o -> o.stopSequences = List.copyOf(values));
    }

    /**/
    public Text safetySettings(List<SafetySetting> values) {
        return withOptions(o -> o.safetySettings = List.copyOf(values));
    }

    /**/
    public Text schema(String schema) {
        return withOptions(o -> o.schema = schema);
    }

    /**/
    public Text protocol(String token) {
        return withOptions(o -> o.proto = token);
    }

    /**/
    public Text caching() {
        return withOptions(o -> o.caching = true);
    }

    /**/
    public Text cacheTtl(int seconds) {
        return withOptions(o -> o.cacheTtl = seconds);
    }

    /**/
    public Text addMiddleware(MiddlewareFn hook) {
        return withOptions(o -> o.middleware.add(hook));
    }

    /*



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

    /*



*/
    public Text file(String id) {
        List<FileRef> files = new java.util.ArrayList<>(inputFiles);
        files.add(new FileRef(id, "", ""));
        return new Text(
                provider, apiKey, baseUrlOverride, http, model, system, options,
                inputImages, List.copyOf(files));
    }

    /*



*/
    private List<Msg> userMsgs(String prompt) {
        if (inputImages.isEmpty() && inputFiles.isEmpty()) {
            return List.of(new Msg.Text("user", prompt));
        }
        return List.of(new Msg.Media("user", prompt, inputImages, inputFiles));
    }

    /*




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

    /*



*/
    public Response stream(String userPrompt, java.util.function.Consumer<String> onDelta) {
        rejectNonDefaultProtocol("stream");
        Providers.Spec config = Providers.config(provider);
        String resolvedModel = resolveModel(config);
        return Streaming.run(
                config, apiKey, resolvedModel, system,
                userMsgs(userPrompt), options, http, baseUrlOverride, onDelta);
    }

    /*




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

    /*


*/
    /*





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

    /**/
    private Text withOptions(java.util.function.Consumer<PromptOptions> mutate) {
        PromptOptions copy = options.copy();
        mutate.accept(copy);
        return new Text(
                provider, apiKey, baseUrlOverride, http, model, system, copy, inputImages, inputFiles);
    }
}
