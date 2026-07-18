package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.MediaRef;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.VideoGenDef;
import com.aktagon.llmkit.providers.generated.VideoHandle;
import com.aktagon.llmkit.providers.generated.VideoModelDef;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Immutable, clone-on-chain builder for asynchronous video generation
 * (ADR-068 Phase 4e, ADR-034) — a port of Swift's {@code Video} / Rust's
 * {@code video.rs} onto the shared Job engine (ADR-062). Asynchronous:
 * {@code client.video().<config>.submit(prompt)} POSTs the provider submit
 * body and returns a live {@link VideoJob} immediately; the job polls the
 * lifecycle to completion ({@link VideoJob#await}) or one round-trip at a time
 * ({@link VideoJob#poll}).
 *
 * <p>Dispatch branches on the generated {@code videoGenConfig(provider).wireShape}
 * fact — never the provider name. Ten wire shapes across eleven fixtures ship:
 * the shared {@code {model, prompt}} arm (Grok/Zhipu/Vidu/Together/MiniMax),
 * Grok's image-to-video seed frame inlined as a data URL at {@code image.url}
 * (BUG-010), the nested Qwen {@code {model, input:{prompt}}} (with the
 * {@code X-DashScope-Async: enable} header), PixVerse's five-field body (+ a
 * per-request {@code Ai-trace-id} header), the model-in-path Veo/Vertex
 * {@code {instances:[{prompt}]}}, and Bedrock Nova Reel's SigV4-signed
 * {@code {modelId, modelInput, outputDataConfig}} (VID-005).
 *
 * <p>The generated {@link VideoHandle} value carries only identity (id +
 * provider + raw + model); the credential-bearing, transport-holding live
 * handle is the handwritten {@link VideoJob} wrapper (mirror of
 * {@link BatchJob}).
 */
public final class Video {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    /** Accumulated seed frames for the image-to-video path (caller order). */
    private final List<MediaRef> inputImages;
    private final VideoOptions options;

    private Video(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            List<MediaRef> inputImages,
            VideoOptions options) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.inputImages = inputImages;
        this.options = options;
    }

    static Video root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Video(provider, apiKey, baseUrlOverride, http, null, List.of(), new VideoOptions());
    }

    /** Select the video-generation model (required). */
    public Video model(String model) {
        return new Video(provider, apiKey, baseUrlOverride, http, model, inputImages, options);
    }

    /**
     * Attach a seed frame for the image-to-video path (BUG-010). The bytes
     * are base64-encoded into a data URL at wire time. Accepted only by
     * models whose {@code VideoModelDef} sets {@code supportsImageToVideo}.
     */
    public Video image(String mimeType, byte[] data) {
        List<MediaRef> images = new ArrayList<>(inputImages);
        images.add(new MediaRef(mimeType, data));
        return new Video(provider, apiKey, baseUrlOverride, http, model, List.copyOf(images), options);
    }

    /**
     * Set the caller-supplied destination S3 URI (required by output-uri
     * delivery providers, e.g. Bedrock Nova Reel; ignored otherwise).
     */
    public Video outputUri(String uri) {
        return withOptions(o -> o.outputUri = uri);
    }

    /** Opt into raw poll bodies on the returned {@code VideoResponse} (ADR-014). */
    public Video raw() {
        return withOptions(o -> o.raw = true);
    }

    /** Register a middleware hook (observation + pre-phase veto). */
    public Video addMiddleware(MiddlewareFn hook) {
        return withOptions(o -> o.middleware.add(hook));
    }

    /**
     * Submit an asynchronous text-to-video (or image-to-video) job and
     * return the live {@link VideoJob}. Pre-flight validation rejects
     * unknown models, unsupported part kinds, and image-to-video on
     * text-only models before any HTTP call. Fires the {@code videoGeneration}
     * middleware op pre + post around the HTTP submit (not the poll loop —
     * batch-submit semantics).
     */
    public VideoJob submit(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ValidationException("api_key", "required");
        }
        if (model == null || model.isEmpty()) {
            throw new ValidationException("model", "required for video generation");
        }
        List<Part> parts = normalizeParts(prompt);

        Providers.Spec config = Providers.config(provider);
        VideoGenDef vgCfg = VideoGenDef.videoGenConfig(provider);
        if (vgCfg == null) {
            throw new ValidationException("provider", config.slug + " does not support video generation");
        }
        VideoModelDef modelDef = findModel(vgCfg, model);
        if (modelDef == null) {
            throw new ValidationException(
                    "model", model + " is not a known video-generation model for " + config.slug);
        }
        validate(parts, modelDef, vgCfg, config.slug);

        Event baseEvent = Event.of(MiddlewareOp.VIDEO_GENERATION, config.slug, model);
        long startNanos = System.nanoTime();
        Middleware.firePre(options.middleware, baseEvent);

        try {
            String id = dispatchSubmit(config, vgCfg, parts);
            Middleware.firePost(
                    options.middleware, baseEvent.toPost("", null, null, Middleware.elapsedMillis(startNanos)));
            VideoHandle handle = new VideoHandle(id, provider, options.raw, model);
            return new VideoJob(handle, apiKey, http, baseUrlOverride);
        } catch (RuntimeException e) {
            Middleware.firePost(
                    options.middleware,
                    baseEvent.toPost("", null, e, Middleware.elapsedMillis(startNanos)));
            throw e;
        }
    }

    /** Clone-on-chain: copy the options, mutate, return a fresh builder. */
    private Video withOptions(Consumer<VideoOptions> mutate) {
        VideoOptions copy = options.copy();
        mutate.accept(copy);
        return new Video(provider, apiKey, baseUrlOverride, http, model, inputImages, copy);
    }

    // --- Parts ---

    /** The internal video-input atom: prompt text plus optional seed frames. */
    private sealed interface Part {
        record Text(String text) implements Part {}

        record ImagePart(MediaRef media) implements Part {}
    }

    private static boolean isImage(Part part) {
        return part instanceof Part.ImagePart;
    }

    /**
     * Prompt-only hot path, or (when seed frames were attached) the parts
     * path with the prompt text appended last. Both empty is a validation
     * error.
     */
    private List<Part> normalizeParts(String prompt) {
        if (!inputImages.isEmpty()) {
            List<Part> parts = new ArrayList<>();
            for (MediaRef ref : inputImages) {
                parts.add(new Part.ImagePart(ref));
            }
            if (prompt != null && !prompt.isEmpty()) {
                parts.add(new Part.Text(prompt));
            }
            return parts;
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

    // --- Validation ---

    /**
     * Pre-flight rejects: image-to-video only on {@code supportsImageToVideo}
     * models (else the seed frame would silently drop at wire time), and
     * output-uri providers require the caller S3 URI (VID-005).
     */
    private void validate(List<Part> parts, VideoModelDef modelDef, VideoGenDef vgCfg, String slug) {
        if (parts.stream().anyMatch(Video::isImage) && !modelDef.supportsImageToVideo()) {
            throw new ValidationException(
                    "parts", model + " is a text-to-video-only model and does not accept image parts");
        }
        if (vgCfg.requiresOutputUri() && options.outputUri.isEmpty()) {
            throw new ValidationException(
                    "output_uri", slug + " requires a caller output S3 URI; set outputUri on the request");
        }
    }

    // --- Submit dispatch (selected by wireShape, never provider name) ---

    /**
     * POSTs the submit body per wire shape and returns the provider-assigned
     * poll handle id (read from the config-declared {@code submitHandleField}
     * dotted path). SigV4 providers (Bedrock) sign the exact bytes via
     * {@link RequestBuilder#send}.
     */
    private String dispatchSubmit(Providers.Spec config, VideoGenDef vgCfg, List<Part> parts) {
        String base = VideoPoll.baseUrl(config, vgCfg, baseUrlOverride);
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

        JsonObject body;
        switch (vgCfg.wireShape()) {
            case "VideoQwen" -> {
                // DashScope's async submit requires this header; per-request only.
                headers.put("X-DashScope-Async", "enable");
                JsonObject input = new JsonObject();
                input.addProperty("prompt", joinPromptText(parts));
                body = new JsonObject();
                body.addProperty("model", model);
                body.add("input", input);
            }
            case "VideoPixVerse" -> {
                // All five fields required; the generic surface is prompt-only,
                // so duration/quality/aspect_ratio are reference-anchored
                // defaults. The per-request Ai-trace-id (PixVerse's anti-cache
                // key) is set per-call.
                headers.put("Ai-trace-id", newTraceId());
                body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("prompt", joinPromptText(parts));
                body.addProperty("duration", 5);
                body.addProperty("quality", "540p");
                body.addProperty("aspect_ratio", "16:9");
            }
            case "VideoVeo", "VideoVertexVeo" -> {
                // Veo / Vertex Veo carry the model in the submit PATH
                // (:predictLongRunning), so the body has no model field.
                JsonObject instance = new JsonObject();
                instance.addProperty("prompt", joinPromptText(parts));
                JsonArray instances = new JsonArray();
                instances.add(instance);
                body = new JsonObject();
                body.add("instances", instances);
            }
            case "VideoBedrock" -> {
                // Nova Reel carries the model in the BODY (modelId) and writes
                // the mp4 to the caller's S3 bucket.
                JsonObject textToVideoParams = new JsonObject();
                textToVideoParams.addProperty("text", joinPromptText(parts));
                JsonObject modelInput = new JsonObject();
                modelInput.addProperty("taskType", "TEXT_VIDEO");
                modelInput.add("textToVideoParams", textToVideoParams);
                JsonObject s3Config = new JsonObject();
                s3Config.addProperty("s3Uri", options.outputUri);
                JsonObject outputDataConfig = new JsonObject();
                outputDataConfig.add("s3OutputDataConfig", s3Config);
                body = new JsonObject();
                body.addProperty("modelId", model);
                body.add("modelInput", modelInput);
                body.add("outputDataConfig", outputDataConfig);
            }
            default -> {
                // Shared {model, prompt} arm (Grok/Zhipu/Vidu/Together/MiniMax).
                // Image-to-video (BUG-010): a seed frame inlines as a data URL
                // at xAI's image.url field (absent on the text-to-video hot path).
                body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("prompt", joinPromptText(parts));
                String seed = seedImageUrl(parts);
                if (seed != null) {
                    JsonObject image = new JsonObject();
                    image.addProperty("url", seed);
                    body.add("image", image);
                }
            }
        }

        // {model} in the submit endpoint is substituted with the per-call
        // model (Veo's :predictLongRunning path); a no-op for body-model
        // providers.
        String url = VideoPoll.appendQueryAuth(
                base + vgCfg.genEndpoint().replace("{model}", model), config, apiKey);

        HttpTransport.Result result = RequestBuilder.send(config, url, body, headers, apiKey, http);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new ApiException(
                    "video_submit", result.statusCode(), new String(result.body(), StandardCharsets.UTF_8));
        }
        JsonElement parsed = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        String id = VideoPoll.lookupHandleField(parsed, vgCfg.submitHandleField());
        if (id.isEmpty()) {
            throw new DecodingException("video submit: empty handle field \"" + vgCfg.submitHandleField() + "\"");
        }
        return id;
    }

    /**
     * The image-to-video seed-frame data URL (BUG-010). Returns null on the
     * text-to-video hot path; rejects more than one seed frame (a
     * single-frame condition, so multi-image is a separate slice — rejecting
     * is honest).
     */
    private static String seedImageUrl(List<Part> parts) {
        MediaRef seed = null;
        for (Part part : parts) {
            if (part instanceof Part.ImagePart imagePart) {
                if (seed != null) {
                    throw new ValidationException(
                            "parts", "image-to-video conditions on a single seed frame; pass one image part");
                }
                seed = imagePart.media();
            }
        }
        if (seed == null) {
            return null;
        }
        String mime = seed.mimeType().isEmpty() ? "image/png" : seed.mimeType();
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(seed.bytes());
    }

    private static VideoModelDef findModel(VideoGenDef cfg, String modelId) {
        for (VideoModelDef m : cfg.models()) {
            if (m.modelId().equals(modelId)) {
                return m;
            }
        }
        return null;
    }

    /**
     * A UUID-shaped, unique-per-request trace id for providers that require
     * one (PixVerse's {@code Ai-trace-id}, an anti-cache key). {@code
     * java.util.UUID} is a real v4 UUID (the Rust twin hand-rolls one only to
     * stay dependency-free).
     */
    static String newTraceId() {
        return UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
    }
}
