package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.MediaRef;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.RequestVariant;
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
 * per-request {@code Ai-trace-id} header), the model-in-path Vertex Veo
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
    /** Ordered text parts sent before the terminal's own prompt argument. */
    private final List<String> textParts;

    private Video(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            List<MediaRef> inputImages,
            VideoOptions options,
            List<String> textParts) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.inputImages = inputImages;
        this.options = options;
        this.textParts = textParts;
    }

    static Video root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Video(provider, apiKey, baseUrlOverride, http, null, List.of(), new VideoOptions(), List.of());
    }

    /** Select the video-generation model (required). */
    public Video model(String model) {
        return new Video(provider, apiKey, baseUrlOverride, http, model, inputImages, options, textParts);
    }

    /**
     * Attach a seed frame for the image-to-video path (BUG-010). The bytes
     * are base64-encoded into a data URL at wire time. Accepted only by
     * models whose {@code VideoModelDef} sets {@code supportsImageToVideo}.
     */
    public Video image(String mimeType, byte[] data) {
        List<MediaRef> images = new ArrayList<>(inputImages);
        images.add(new MediaRef(mimeType, data));
        return new Video(provider, apiKey, baseUrlOverride, http, model, List.copyOf(images), options, textParts);
    }

    /**
     * Set the final frame the video ends on. Send it with a first frame
     * ({@link #image}) on a model that takes a last frame; otherwise it is
     * rejected before send (BUG-089).
     */
    public Video lastFrame(String mimeType, byte[] data) {
        return withOptions(o -> o.lastFrame = new MediaRef(mimeType, data));
    }

    /**
     * Append an asset reference image, such as a person, character or
     * product the video keeps. Add at most 3, without {@link #image} or
     * {@link #lastFrame} (BUG-089).
     */
    public Video addReferenceImage(String mimeType, byte[] data) {
        return withOptions(o -> o.referenceImages.add(new MediaRef(mimeType, data)));
    }

    /**
     * Set the video length in whole seconds. OpenRouter, Grok and Vertex Veo
     * send it; other providers reject it before send, as does a value
     * above the model's maximum (HANDOFF-097, HANDOFF-100).
     */
    public Video duration(int seconds) {
        return withOptions(o -> o.durationSeconds = seconds);
    }

    /**
     * Set the output aspect ratio, such as "16:9". Sent on the same providers
     * as {@link #duration}; a ratio the model does not list is rejected before
     * send (HANDOFF-097, HANDOFF-100).
     */
    public Video aspectRatio(String ratio) {
        return withOptions(o -> o.aspectRatio = ratio);
    }

    /**
     * Set the output resolution, such as "720p". Sent on the same providers as
     * {@link #duration}; a resolution the model does not list is rejected
     * before send (HANDOFF-097, HANDOFF-100).
     */
    public Video resolution(String resolution) {
        return withOptions(o -> o.resolution = resolution);
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
     * Append a text part. Text parts are sent in call order, before the
     * terminal's own prompt argument, joined into the prompt.
     */
    public Video text(String value) {
        List<String> parts = new ArrayList<>(textParts);
        parts.add(value);
        return new Video(provider, apiKey, baseUrlOverride, http, model, inputImages, options, List.copyOf(parts));
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
        RequestVariant variant = validate(parts, modelDef, vgCfg, config.slug);

        Event baseEvent = Event.of(MiddlewareOp.VIDEO_GENERATION, config.slug, model);
        long startNanos = System.nanoTime();
        Middleware.firePre(options.middleware, baseEvent);

        try {
            String id = dispatchSubmit(config, vgCfg, variant, parts);
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
        return new Video(provider, apiKey, baseUrlOverride, http, model, inputImages, copy, textParts);
    }

    // --- Parts ---

    /** The internal video-input atom: prompt text plus optional seed frames. */
    sealed interface Part {
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
        List<Part> texts = new ArrayList<>();
        for (String text : textParts) {
            if (!text.isEmpty()) {
                texts.add(new Part.Text(text));
            }
        }
        if (prompt != null && !prompt.isEmpty()) {
            texts.add(new Part.Text(prompt));
        }
        if (!inputImages.isEmpty()) {
            List<Part> parts = new ArrayList<>();
            for (MediaRef ref : inputImages) {
                parts.add(new Part.ImagePart(ref));
            }
            parts.addAll(texts);
            return parts;
        }
        if (texts.isEmpty()) {
            throw new ValidationException("prompt", "set either prompt or parts");
        }
        return texts;
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
     * models (else the seed frame would silently drop at wire time), a seed
     * frame selects the WithSeedFrame submit request (ADR-096), and
     * output-uri providers require the caller S3 URI (VID-005). Returns the
     * selected request variant.
     */
    private RequestVariant validate(List<Part> parts, VideoModelDef modelDef, VideoGenDef vgCfg, String slug) {
        long imageCount = parts.stream().filter(Video::isImage).count();
        if (imageCount > 0 && !modelDef.supportsImageToVideo()) {
            throw new ValidationException(
                    "parts", model + " is a text-to-video-only model and does not accept image parts");
        }
        validateFrames(modelDef, imageCount);
        validateOptions(slug, vgCfg, modelDef);
        // ADR-096: the carried inputs select the submit request; a config with
        // no such variant cannot send it.
        RequestVariant variant = RequestVariants.select(slug, vgCfg.variants(), carries(imageCount));
        if (vgCfg.requiresOutputUri() && options.outputUri.isEmpty()) {
            throw new ValidationException(
                    "output_uri", slug + " requires a caller output S3 URI; set outputUri on the request");
        }
        return variant;
    }

    /** The most asset reference images a Vertex Veo request takes (Vertex Veo page, read 2026-10-15). */
    static final int MAX_REFERENCE_IMAGES = 3;

    /**
     * Rejects a last frame or reference images the model or the request
     * cannot carry, before any HTTP call (BUG-089). Same rules, order and
     * messages as Go's {@code validateVideoFrames}.
     */
    private void validateFrames(VideoModelDef modelDef, long imageCount) {
        int refs = options.referenceImages.size();
        boolean last = options.lastFrame != null;
        if (last && !modelDef.supportsLastFrame()) {
            throw new ValidationException("last_frame", modelDef.modelId() + " does not accept a last frame");
        }
        if (refs > 0 && !modelDef.supportsReferenceImages()) {
            throw new ValidationException(
                    "reference_images", modelDef.modelId() + " does not accept reference images");
        }
        if (refs > MAX_REFERENCE_IMAGES) {
            throw new ValidationException(
                    "reference_images", "at most " + MAX_REFERENCE_IMAGES + " reference images, got " + refs);
        }
        if (refs > 0 && (imageCount > 0 || last)) {
            // Vertex documents the inputs as exclusive (live 2026-10-15, the
            // Gemini API Veo answered 400 "Unsupported video generation request").
            throw new ValidationException("reference_images", "cannot be combined with a first or last frame");
        }
        if (last && imageCount == 0) {
            throw new ValidationException("last_frame", "requires a first frame");
        }
    }

    /**
     * Rejects a duration, aspect ratio or resolution the provider or the
     * model cannot take, before any HTTP call (HANDOFF-097). Same rules,
     * order and messages as Go's {@code validateVideoOptions}: a set option
     * on a wire shape with no body field for it (see
     * {@link #shapeSendsOptions}; first of duration, aspect_ratio,
     * resolution), a negative duration, a duration above the model maximum
     * (0 = no limit), a duration other than 8 with reference images (Veo
     * forces 8 seconds; HANDOFF-100), then an unlisted resolution and an
     * unlisted aspect ratio (empty list = no fixed set).
     */
    private void validateOptions(String slug, VideoGenDef vgCfg, VideoModelDef modelDef) {
        if (!shapeSendsOptions(vgCfg.wireShape())) {
            String field = options.durationSeconds != 0 ? "duration"
                    : !options.aspectRatio.isEmpty() ? "aspect_ratio"
                    : !options.resolution.isEmpty() ? "resolution"
                    : null;
            if (field != null) {
                throw new ValidationException(field, slug + " video does not send " + field);
            }
            return;
        }
        int duration = options.durationSeconds;
        if (duration < 0) {
            throw new ValidationException("duration", "must be positive");
        }
        if (duration > 0 && modelDef.maxDurationSeconds() > 0 && duration > modelDef.maxDurationSeconds()) {
            throw new ValidationException("duration", modelDef.modelId() + " takes at most "
                    + modelDef.maxDurationSeconds() + " seconds, got " + duration);
        }
        if (duration > 0 && !options.referenceImages.isEmpty() && duration != 8) {
            throw new ValidationException("duration", "reference images take 8 seconds, got " + duration);
        }
        if (!options.resolution.isEmpty() && !modelDef.resolutions().isEmpty()
                && !modelDef.resolutions().contains(options.resolution)) {
            throw new ValidationException(
                    "resolution", modelDef.modelId() + " does not support resolution " + options.resolution);
        }
        if (!options.aspectRatio.isEmpty() && !modelDef.aspectRatios().isEmpty()
                && !modelDef.aspectRatios().contains(options.aspectRatio)) {
            throw new ValidationException(
                    "aspect_ratio", modelDef.modelId() + " does not support aspect ratio " + options.aspectRatio);
        }
    }

    /**
     * Whether a video wire shape has body fields for duration, aspect ratio
     * and resolution (HANDOFF-097, HANDOFF-100).
     */
    private static boolean shapeSendsOptions(String wireShape) {
        return switch (wireShape) {
            case "VideoOpenRouter", "VideoGrok", "VideoVertexVeo" -> true;
            default -> false;
        };
    }

    /**
     * Sets the body-root duration, aspect_ratio and resolution of the
     * VideoOpenRouter and VideoGrok bodies (HANDOFF-097, HANDOFF-100), each
     * only when set, so the prompt-only body stays {model, prompt}. duration
     * is a JSON integer; validateOptions has already checked each value
     * against the model.
     */
    private void setRootVideoOptions(JsonObject body) {
        if (options.durationSeconds > 0) {
            body.addProperty("duration", options.durationSeconds);
        }
        if (!options.aspectRatio.isEmpty()) {
            body.addProperty("aspect_ratio", options.aspectRatio);
        }
        if (!options.resolution.isEmpty()) {
            body.addProperty("resolution", options.resolution);
        }
    }

    /** The request's carried inputs (ADR-096): reference images, a seed frame with an optional last frame, or text only. */
    private List<String> carries(long imageCount) {
        if (!options.referenceImages.isEmpty()) {
            return List.of(RequestVariant.WITH_REFERENCE_IMAGES);
        }
        if (imageCount > 0 && options.lastFrame != null) {
            return List.of(RequestVariant.WITH_SEED_FRAME, RequestVariant.WITH_LAST_FRAME);
        }
        return RequestVariants.partsCarry(imageCount, RequestVariant.WITH_SEED_FRAME);
    }

    /** One OpenRouter frame_images item. */
    private static JsonObject openRouterFrame(MediaRef media, String frameType) {
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:" + seedMime(media) + ";base64," + Base64.getEncoder().encodeToString(media.bytes()));
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "image_url");
        frame.add("image_url", imageUrl);
        frame.addProperty("frame_type", frameType);
        return frame;
    }

    /** A Veo image object: raw base64 bytes and the MIME type. */
    private static JsonObject veoImage(MediaRef media) {
        JsonObject image = new JsonObject();
        image.addProperty("bytesBase64Encoded", Base64.getEncoder().encodeToString(media.bytes()));
        image.addProperty("mimeType", seedMime(media));
        return image;
    }

    // --- Submit dispatch (selected by wireShape, never provider name) ---

    /**
     * POSTs the submit body per wire shape and returns the provider-assigned
     * poll handle id (read from the config-declared {@code submitHandleField}
     * dotted path). SigV4 providers (Bedrock) sign the exact bytes via
     * {@link RequestBuilder#send}.
     */
    String dispatchSubmit(
            Providers.Spec config, VideoGenDef vgCfg, RequestVariant variant, List<Part> parts) {
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
            case "VideoVertexVeo" -> {
                // Vertex Veo carries the model in the submit PATH
                // (:predictLongRunning), so the body has no model field.
                JsonObject instance = new JsonObject();
                instance.addProperty("prompt", joinPromptText(parts));
                // First frame (BUG-089): the seed rides at instances[0].image as
                // raw base64 + mimeType (no data: prefix), per the Vertex Veo
                // reference (read 2026-10-15).
                MediaRef veoSeed = seedImage(parts);
                if (veoSeed != null) {
                    instance.add("image", veoImage(veoSeed));
                }
                // Last frame and asset reference images (BUG-089): same
                // {bytesBase64Encoded, mimeType} wrapper. Reference images force
                // an 8-second video, sent as the NUMBER 8.
                if (options.lastFrame != null) {
                    instance.add("lastFrame", veoImage(options.lastFrame));
                }
                JsonArray instances = new JsonArray();
                instances.add(instance);
                body = new JsonObject();
                body.add("instances", instances);
                JsonObject parameters = new JsonObject();
                if (!options.referenceImages.isEmpty()) {
                    JsonArray refs = new JsonArray();
                    for (MediaRef ref : options.referenceImages) {
                        JsonObject item = new JsonObject();
                        item.add("image", veoImage(ref));
                        item.addProperty("referenceType", "asset");
                        refs.add(item);
                    }
                    instance.add("referenceImages", refs);
                    parameters.addProperty("durationSeconds", 8);
                }
                // Video options (HANDOFF-100): parameters.durationSeconds (a
                // JSON number), aspectRatio and resolution, each only when set.
                // With reference images validateOptions allows only 8, so a set
                // duration matches the forced 8.
                if (options.durationSeconds > 0) {
                    parameters.addProperty("durationSeconds", options.durationSeconds);
                }
                if (!options.aspectRatio.isEmpty()) {
                    parameters.addProperty("aspectRatio", options.aspectRatio);
                }
                if (!options.resolution.isEmpty()) {
                    parameters.addProperty("resolution", options.resolution);
                }
                if (parameters.size() > 0) {
                    body.add("parameters", parameters);
                }
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
            case "VideoOpenRouter" -> {
                // Image-to-video (HANDOFF-091): each frame is an OpenRouter
                // ContentPartImage plus frame_type, first_frame then last_frame
                // (live probe 2026-10-18; openrouter.ai/openapi.json).
                body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("prompt", joinPromptText(parts));
                MediaRef orSeed = seedImage(parts);
                if (orSeed != null) {
                    JsonArray frames = new JsonArray();
                    frames.add(openRouterFrame(orSeed, "first_frame"));
                    if (options.lastFrame != null) {
                        frames.add(openRouterFrame(options.lastFrame, "last_frame"));
                    }
                    body.add("frame_images", frames);
                }
                setRootVideoOptions(body);
            }
            case "VideoGrok", "VideoZhipu", "VideoVidu", "VideoTogether", "VideoMinimax" -> {
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
                // Only VideoGrok in this arm sends the options (HANDOFF-100);
                // the other shapes reject a set option in validateOptions.
                if ("VideoGrok".equals(vgCfg.wireShape())) {
                    setRootVideoOptions(body);
                }
            }
            default -> throw new IllegalStateException(
                    "video submit: unsupported wire shape \"" + vgCfg.wireShape() + "\"");
        }

        // The submit endpoint is the selected request variant's (ADR-096).
        // {model} in it is substituted with the per-call
        // model (Veo's :predictLongRunning path); a no-op for body-model
        // providers.
        String url = VideoPoll.appendQueryAuth(
                base + variant.endpoint().replace("{model}", model), config, apiKey);

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
     * The single image-to-video seed frame (Grok, BUG-010; Vertex Veo first
     * frame, BUG-089). Returns null on the text-to-video hot path; rejects more than
     * one seed frame (a single-frame condition, so rejecting is honest).
     */
    private static MediaRef seedImage(List<Part> parts) {
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
        return seed;
    }

    /** The seed frame's MIME, defaulting to image/png. */
    private static String seedMime(MediaRef seed) {
        return seed.mimeType().isEmpty() ? "image/png" : seed.mimeType();
    }

    /**
     * The Grok image-to-video seed-frame data URL (BUG-010). Returns null on
     * the text-to-video hot path.
     */
    private static String seedImageUrl(List<Part> parts) {
        MediaRef seed = seedImage(parts);
        if (seed == null) {
            return null;
        }
        return "data:" + seedMime(seed) + ";base64," + Base64.getEncoder().encodeToString(seed.bytes());
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
