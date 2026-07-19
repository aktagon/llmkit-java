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

/*






















*/
public final class Video {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    /**/
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

    /**/
    public Video model(String model) {
        return new Video(provider, apiKey, baseUrlOverride, http, model, inputImages, options);
    }

    /*



*/
    public Video image(String mimeType, byte[] data) {
        List<MediaRef> images = new ArrayList<>(inputImages);
        images.add(new MediaRef(mimeType, data));
        return new Video(provider, apiKey, baseUrlOverride, http, model, List.copyOf(images), options);
    }

    /*


*/
    public Video outputUri(String uri) {
        return withOptions(o -> o.outputUri = uri);
    }

    /**/
    public Video raw() {
        return withOptions(o -> o.raw = true);
    }

    /**/
    public Video addMiddleware(MiddlewareFn hook) {
        return withOptions(o -> o.middleware.add(hook));
    }

    /*






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

    /**/
    private Video withOptions(Consumer<VideoOptions> mutate) {
        VideoOptions copy = options.copy();
        mutate.accept(copy);
        return new Video(provider, apiKey, baseUrlOverride, http, model, inputImages, copy);
    }

    //

    /**/
    private sealed interface Part {
        record Text(String text) implements Part {}

        record ImagePart(MediaRef media) implements Part {}
    }

    private static boolean isImage(Part part) {
        return part instanceof Part.ImagePart;
    }

    /*



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

    //

    /*



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

    //

    /*




*/
    private String dispatchSubmit(Providers.Spec config, VideoGenDef vgCfg, List<Part> parts) {
        String base = VideoPoll.baseUrl(config, vgCfg, baseUrlOverride);
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

        JsonObject body;
        switch (vgCfg.wireShape()) {
            case "VideoQwen" -> {
                //
                headers.put("X-DashScope-Async", "enable");
                JsonObject input = new JsonObject();
                input.addProperty("prompt", joinPromptText(parts));
                body = new JsonObject();
                body.addProperty("model", model);
                body.add("input", input);
            }
            case "VideoPixVerse" -> {
                //
                //
                //
                //
                headers.put("Ai-trace-id", newTraceId());
                body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("prompt", joinPromptText(parts));
                body.addProperty("duration", 5);
                body.addProperty("quality", "540p");
                body.addProperty("aspect_ratio", "16:9");
            }
            case "VideoVeo", "VideoVertexVeo" -> {
                //
                //
                JsonObject instance = new JsonObject();
                instance.addProperty("prompt", joinPromptText(parts));
                JsonArray instances = new JsonArray();
                instances.add(instance);
                body = new JsonObject();
                body.add("instances", instances);
            }
            case "VideoBedrock" -> {
                //
                //
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
                //
                //
                //
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

        //
        //
        //
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

    /*




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

    /*




*/
    static String newTraceId() {
        return UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
    }
}
