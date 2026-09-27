package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ImageData;
import com.aktagon.llmkit.providers.generated.ImageGenDef;
import com.aktagon.llmkit.providers.generated.ImageModelDef;
import com.aktagon.llmkit.providers.generated.ImageResponse;
import com.aktagon.llmkit.providers.generated.MediaRef;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Immutable, clone-on-chain builder for image generation (ADR-068 Phase 4c,
 * BUG-024 discipline) — a port of Swift's {@code Image} / Rust's
 * {@code image.rs}. Synchronous: {@code client.image().<config>.generate(prompt)}
 * builds the provider request body, sends it once, and parses the reply into
 * the universal {@link ImageResponse}.
 *
 * <p>The generated {@code imageGenConfig(provider)} fact selects both the
 * request-body shape ({@code inputMode}) and the response parser
 * ({@code responseShape}) — never the provider name (BUG-024). Pre-flight
 * validation rejects unsupported aspect ratios / sizes / reference-image
 * counts before any HTTP call.
 *
 * <p>Scope: the JSON generation path for every provider, plus OpenAI's
 * {@code multipart/form-data} edit branch ({@code image[]} files and an
 * optional {@code mask}; mirror of Go).
 */
public final class Image {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    private final ImageOptions options;
    /** Accumulated reference images for the edit path (caller order preserved). */
    private final List<MediaRef> inputImages;
    /** Ordered text parts sent before the terminal's own prompt argument. */
    private final List<String> textParts;

    private Image(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            ImageOptions options,
            List<MediaRef> inputImages,
            List<String> textParts) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.options = options;
        this.inputImages = inputImages;
        this.textParts = textParts;
    }

    static Image root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Image(provider, apiKey, baseUrlOverride, http, null, new ImageOptions(), List.of(), List.of());
    }

    /** Select the image-generation model. */
    public Image model(String model) {
        return new Image(provider, apiKey, baseUrlOverride, http, model, options, inputImages, textParts);
    }

    /** Request an aspect ratio (validated against the model's whitelist). */
    public Image aspectRatio(String value) {
        return withOptions(o -> o.aspectRatio = value);
    }

    /**
     * Request an image size (validated against the model's whitelist; OpenAI /
     * Recraft take a {@code WxH} size passed through unchecked).
     */
    public Image imageSize(String value) {
        return withOptions(o -> o.imageSize = value);
    }

    /** Ask the provider to return text alongside the image (Google only). */
    public Image includeText() {
        return withOptions(o -> o.includeText = true);
    }

    /** OpenAI gpt-image-* quality (low|medium|high|auto). */
    public Image quality(String value) {
        return withOptions(o -> o.quality = value);
    }

    /** OpenAI gpt-image-* output MIME format (png|webp|jpeg). */
    public Image outputFormat(String value) {
        return withOptions(o -> o.outputFormat = value);
    }

    /** OpenAI gpt-image-* background treatment (transparent|opaque|auto). */
    public Image background(String value) {
        return withOptions(o -> o.background = value);
    }

    /** Number of images to generate; wire field {@code n} (OpenAI + Recraft + xAI). */
    public Image count(int value) {
        return withOptions(o -> o.count = value);
    }

    /**
     * Attach a reference image for the edit path. The bytes are base64-encoded
     * at wire time. Multiple {@code .image(...)} calls accumulate in caller
     * order.
     */
    public Image image(String mimeType, byte[] data) {
        List<MediaRef> images = new ArrayList<>(inputImages);
        images.add(new MediaRef(mimeType, data));
        return new Image(provider, apiKey, baseUrlOverride, http, model, options, List.copyOf(images), textParts);
    }

    /** Register a middleware hook (observation + pre-phase veto). */
    public Image addMiddleware(MiddlewareFn hook) {
        return withOptions(o -> o.middleware.add(hook));
    }

    /**
     * Append a text part. Text parts are sent in call order, after any
     * reference images and before the terminal's own prompt argument.
     */
    public Image text(String value) {
        List<String> parts = new ArrayList<>(textParts);
        parts.add(value);
        return new Image(provider, apiKey, baseUrlOverride, http, model, options, inputImages, List.copyOf(parts));
    }

    /**
     * Attach an inpainting mask (transparent pixels mark the region to edit).
     * OpenAI edits (requires a reference image) and Vertex Imagen only; other
     * providers reject it before any HTTP call.
     */
    public Image mask(String mimeType, byte[] data) {
        MediaRef ref = new MediaRef(mimeType, data.clone());
        return withOptions(o -> o.mask = ref);
    }

    /** Vertex Imagen global safety threshold ({@code parameters.safetySetting}); rejected elsewhere. */
    public Image safetyFilter(String threshold) {
        return withOptions(o -> o.safetyFilter = threshold);
    }

    /** Google per-category safety thresholds ({@code safetySettings[]}); rejected elsewhere. */
    public Image safetySettings(List<SafetySetting> values) {
        return withOptions(o -> o.safetySettings = new ArrayList<>(values));
    }

    /** Attach the provider's response body to the ImageResponse as {@code raw} (ADR-014). */
    public Image raw() {
        return withOptions(o -> o.raw = true);
    }

    /**
     * Build and send the image request, returning the decoded
     * {@link ImageResponse}. Fires the {@code imageGeneration} middleware op
     * (pre-phase veto, post-phase observation with usage).
     */
    public ImageResponse generate(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ValidationException("api_key", "required");
        }
        if (model == null || model.isEmpty()) {
            throw new ValidationException("model", "required for image generation");
        }
        List<Part> parts = normalizeParts(prompt);

        Providers.Spec config = Providers.config(provider);
        ImageGenDef imgCfg = ImageGenDef.imageGenConfig(provider);
        if (imgCfg == null) {
            throw new ValidationException("provider", config.slug + " does not support image generation");
        }
        ImageModelDef modelDef = findModel(imgCfg, model);
        if (modelDef == null) {
            throw new ValidationException(
                    "model", model + " is not a known image-generation model for " + config.slug);
        }
        validate(parts, modelDef, imgCfg, config.slug);

        Event baseEvent = Event.of(MiddlewareOp.IMAGE_GENERATION, config.slug, model);
        long startNanos = System.nanoTime();
        Middleware.firePre(options.middleware, baseEvent);

        try {
            ImageResponse response = send(parts, imgCfg, config);
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

    /** Clone-on-chain: copy the options, mutate, return a fresh builder. */
    private Image withOptions(Consumer<ImageOptions> mutate) {
        ImageOptions copy = options.copy();
        mutate.accept(copy);
        return new Image(provider, apiKey, baseUrlOverride, http, model, copy, inputImages, textParts);
    }

    // --- Parts ---

    /**
     * The internal image-input atom: either the accumulated reference images
     * followed by the prompt text (edit path), or the bare prompt text
     * (generation path). Mirrors Swift's {@code ImagePart} / Rust's {@code Part}.
     */
    private sealed interface Part {
        record Text(String text) implements Part {}

        record ImagePart(MediaRef media) implements Part {}
    }

    private static boolean isImage(Part part) {
        return part instanceof Part.ImagePart;
    }

    /**
     * Enforce the prompt-XOR-parts rule and produce the canonical part list. A
     * chain that accumulated reference images uses the parts path (text
     * appended last); otherwise the prompt sugar path. Both empty is a
     * validation error.
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

    private static String joinText(List<Part> parts) {
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
     * Per-provider pre-flight rejects. Aspect ratio / size are checked against
     * the model whitelist (empty = pass-through); reference-image count against
     * {@code maxInputCount}; the OpenAI-only knobs (quality / output_format /
     * background / count) are rejected on the wire shapes that do not accept
     * them. Mirror of Swift's {@code validate}.
     */
    private void validate(List<Part> parts, ImageModelDef modelDef, ImageGenDef imgCfg, String slug) {
        if (options.aspectRatio != null
                && !modelDef.aspectRatios().isEmpty()
                && !modelDef.aspectRatios().contains(options.aspectRatio)) {
            throw new ValidationException("aspect_ratio", options.aspectRatio + " not supported by " + model);
        }
        if (options.imageSize != null
                && !modelDef.imageSizes().isEmpty()
                && !modelDef.imageSizes().contains(options.imageSize)) {
            throw new ValidationException("image_size", options.imageSize + " not supported by " + model);
        }
        long imageCount = parts.stream().filter(Image::isImage).count();
        if (imageCount > imgCfg.maxInputCount()) {
            throw new ValidationException(
                    "parts",
                    imageCount + " image parts exceeds maximum " + imgCfg.maxInputCount() + " for " + slug);
        }

        // Per-provider knob validation. Quality / output_format / background are
        // OpenAI-only on the wire; count (n) is OpenAI + Recraft + xAI. Recraft
        // additionally has no aspect_ratio wire field (it sizes by WxH).
        //
        // Mask is OpenAI edits (MultipartForm with image parts) + Vertex only;
        // safetyFilter is Vertex only; safetySettings is Google only (Go parity).
        Object safetySettings = options.safetySettings.isEmpty() ? null : options.safetySettings;
        if ("InlineParts".equals(imgCfg.inputMode())) {
            reject(options.quality, "quality", slug);
            reject(options.outputFormat, "output_format", slug);
            reject(options.background, "background", slug);
            reject(options.count, "count", slug);
            reject(options.mask, "mask", slug);
            if (options.safetyFilter != null) {
                throw new ValidationException(
                        "safety_filter", "not supported by " + slug + "; use safetySettings for text-gen");
            }
        } else if ("JSONInlineRefs".equals(imgCfg.inputMode())) {
            reject(options.quality, "quality", slug);
            reject(options.outputFormat, "output_format", slug);
            reject(options.background, "background", slug);
            reject(options.mask, "mask", slug);
            reject(options.safetyFilter, "safety_filter", slug);
            reject(safetySettings, "safety_settings", slug);
        } else if ("JSONPredict".equals(imgCfg.inputMode())) {
            reject(options.quality, "quality", slug);
            reject(options.outputFormat, "output_format", slug);
            reject(options.background, "background", slug);
            if (safetySettings != null) {
                throw new ValidationException(
                        "safety_settings", "not supported by " + slug + "; use safetyFilter for Vertex Imagen");
            }
        } else if ("JSONGenerations".equals(imgCfg.inputMode())) {
            if (options.aspectRatio != null) {
                throw new ValidationException(
                        "aspect_ratio", "not supported by " + slug + "; use image_size (Recraft sizes by WxH)");
            }
            reject(options.quality, "quality", slug);
            reject(options.outputFormat, "output_format", slug);
            reject(options.background, "background", slug);
            reject(options.mask, "mask", slug);
            reject(options.safetyFilter, "safety_filter", slug);
            reject(safetySettings, "safety_settings", slug);
        } else { // MultipartForm: quality / output_format / background / count all valid.
            if (options.mask != null && imageCount == 0) {
                throw new ValidationException("mask", "requires at least one image part (edits branch only)");
            }
            reject(options.safetyFilter, "safety_filter", slug);
            reject(safetySettings, "safety_settings", slug);
        }
    }

    private static void reject(Object value, String field, String slug) {
        if (value != null) {
            throw new ValidationException(field, "not supported by " + slug);
        }
    }

    // --- Send ---

    private ImageResponse send(List<Part> parts, ImageGenDef imgCfg, Providers.Spec config) {
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);
        boolean hasImages = parts.stream().anyMatch(Image::isImage);

        String url;
        JsonObject body;
        if ("MultipartForm".equals(imgCfg.inputMode()) && hasImages) {
            HttpTransport.Result result = http.postMultipart(
                    base + imgCfg.editEndpoint(), openAIEditFields(parts), openAIEditFiles(parts), headers);
            return decode(result, imgCfg, config);
        }
        if ("JSONInlineRefs".equals(imgCfg.inputMode())) {
            body = hasImages ? buildXAIEditBody(parts) : buildXAIGenBody(parts);
            url = base + (hasImages ? imgCfg.editEndpoint() : imgCfg.genEndpoint());
        } else if ("JSONGenerations".equals(imgCfg.inputMode())) {
            body = buildRecraftBody(parts);
            url = base + imgCfg.genEndpoint();
        } else if ("MultipartForm".equals(imgCfg.inputMode())) {
            body = buildOpenAIBody(parts);
            url = base + imgCfg.genEndpoint();
        } else if ("JSONPredict".equals(imgCfg.inputMode())) {
            body = buildVertexBody(parts);
            url = RequestBuilder.buildUrl(config, config.endpoint, apiKey, model, baseUrlOverride);
        } else { // InlineParts (Google generateContent)
            body = buildGoogleBody(parts);
            url = RequestBuilder.buildUrl(config, config.endpoint, apiKey, model, baseUrlOverride);
        }

        return decode(http.postJson(url, Json.serialize(body), headers), imgCfg, config);
    }

    /** Status check, then the config-selected parser; attaches {@code raw} when opted in (ADR-014). */
    private ImageResponse decode(HttpTransport.Result result, ImageGenDef imgCfg, Providers.Spec config) {
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw ResponseCodec.parseError(config, result.statusCode(), result.body());
        }
        JsonElement raw = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        // Response parser selected by config shape, never provider name (BUG-024).
        ImageResponse parsed = parseResponse(raw, imgCfg);
        if (!options.raw) {
            return parsed;
        }
        return new ImageResponse(
                parsed.images(), parsed.text(), parsed.usage(), parsed.finishReason(), parsed.finishMessage(), raw);
    }

    /**
     * OpenAI {@code /v1/images/edits} text fields: model, the joined prompt,
     * then the optional gpt-image knobs (mirror of Go's
     * {@code buildOpenAIEditMultipart}).
     */
    private Map<String, String> openAIEditFields(List<Part> parts) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("model", model);
        fields.put("prompt", joinText(parts));
        if (options.imageSize != null) {
            fields.put("size", options.imageSize);
        }
        if (options.quality != null) {
            fields.put("quality", options.quality);
        }
        if (options.outputFormat != null) {
            fields.put("output_format", options.outputFormat);
        }
        if (options.background != null) {
            fields.put("background", options.background);
        }
        if (options.count != null) {
            fields.put("n", String.valueOf(options.count));
        }
        return fields;
    }

    /** One {@code image[]} file per reference image in caller order, then the optional {@code mask}. */
    private List<Multipart.FilePart> openAIEditFiles(List<Part> parts) {
        List<Multipart.FilePart> files = new ArrayList<>();
        int index = 0;
        for (Part part : parts) {
            if (part instanceof Part.ImagePart imagePart) {
                MediaRef media = imagePart.media();
                String mime = media.mimeType().isEmpty() ? "image/png" : media.mimeType();
                files.add(new Multipart.FilePart("image[]", "image-" + index + extFromMime(mime), mime, media.bytes()));
                index++;
            }
        }
        if (options.mask != null) {
            String mime = options.mask.mimeType().isEmpty() ? "image/png" : options.mask.mimeType();
            files.add(new Multipart.FilePart("mask", "mask" + extFromMime(mime), mime, options.mask.bytes()));
        }
        return files;
    }

    private static String extFromMime(String mime) {
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }

    // --- Request bodies ---

    /**
     * Google {@code generateContent} body (InlineParts): a single content turn
     * whose parts carry text and {@code inlineData} images in caller order,
     * plus the {@code generationConfig} response modalities + optional
     * {@code imageConfig}.
     */
    private JsonObject buildGoogleBody(List<Part> parts) {
        JsonArray wire = new JsonArray();
        for (Part part : parts) {
            if (part instanceof Part.Text text) {
                JsonObject block = new JsonObject();
                block.addProperty("text", text.text());
                wire.add(block);
            } else if (part instanceof Part.ImagePart imagePart) {
                MediaRef media = imagePart.media();
                JsonObject inlineData = new JsonObject();
                inlineData.addProperty("mimeType", media.mimeType());
                inlineData.addProperty("data", Base64.getEncoder().encodeToString(media.bytes()));
                JsonObject block = new JsonObject();
                block.add("inlineData", inlineData);
                wire.add(block);
            }
        }

        JsonArray modalities = new JsonArray();
        if (options.includeText) {
            modalities.add("TEXT");
        }
        modalities.add("IMAGE");

        JsonObject generationConfig = new JsonObject();
        generationConfig.add("responseModalities", modalities);
        JsonObject imageConfig = new JsonObject();
        if (options.aspectRatio != null) {
            imageConfig.addProperty("aspectRatio", options.aspectRatio);
        }
        if (options.imageSize != null) {
            imageConfig.addProperty("imageSize", options.imageSize);
        }
        if (imageConfig.size() > 0) {
            generationConfig.add("imageConfig", imageConfig);
        }

        JsonObject content = new JsonObject();
        content.add("parts", wire);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);
        if (!options.safetySettings.isEmpty()) {
            JsonArray settings = new JsonArray();
            for (SafetySetting setting : options.safetySettings) {
                JsonObject entry = new JsonObject();
                entry.addProperty("category", setting.category());
                entry.addProperty("threshold", setting.threshold());
                settings.add(entry);
            }
            body.add("safetySettings", settings);
        }
        return body;
    }

    /**
     * OpenAI {@code /v1/images/generations} JSON body. gpt-image-* always
     * returns base64 and rejects {@code response_format}, so it is never set.
     */
    private JsonObject buildOpenAIBody(List<Part> parts) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", joinText(parts));
        if (options.imageSize != null) {
            body.addProperty("size", options.imageSize);
        }
        if (options.quality != null) {
            body.addProperty("quality", options.quality);
        }
        if (options.outputFormat != null) {
            body.addProperty("output_format", options.outputFormat);
        }
        if (options.background != null) {
            body.addProperty("background", options.background);
        }
        if (options.count != null) {
            body.addProperty("n", options.count);
        }
        return body;
    }

    /**
     * Recraft {@code /v1/images/generations} JSON body. {@code response_format}
     * is forced to {@code b64_json} (Recraft defaults to URL delivery) so the
     * decode path is uniform; vector/SVG output is selected by the model id,
     * not a body flag.
     */
    private JsonObject buildRecraftBody(List<Part> parts) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", joinText(parts));
        body.addProperty("response_format", "b64_json");
        if (options.imageSize != null) {
            body.addProperty("size", options.imageSize);
        }
        if (options.count != null) {
            body.addProperty("n", options.count);
        }
        return body;
    }

    /**
     * xAI Grok {@code /v1/images/generations} JSON body. {@code image_size}
     * maps to {@code resolution} (xAI's name); {@code response_format=b64_json}
     * is forced (xAI defaults to URL).
     */
    private JsonObject buildXAIGenBody(List<Part> parts) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", joinText(parts));
        body.addProperty("response_format", "b64_json");
        if (options.aspectRatio != null) {
            body.addProperty("aspect_ratio", options.aspectRatio);
        }
        if (options.imageSize != null) {
            body.addProperty("resolution", options.imageSize);
        }
        if (options.count != null) {
            body.addProperty("n", options.count);
        }
        return body;
    }

    /**
     * xAI Grok {@code /v1/images/edits} body: one reference image maps to
     * {@code image: {url: "data:..."}}, multiple to {@code images: [...]} in
     * caller order.
     */
    private JsonObject buildXAIEditBody(List<Part> parts) {
        JsonObject body = buildXAIGenBody(parts);
        JsonArray refs = new JsonArray();
        for (Part part : parts) {
            if (part instanceof Part.ImagePart imagePart) {
                MediaRef media = imagePart.media();
                String mime = media.mimeType().isEmpty() ? "image/png" : media.mimeType();
                String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(media.bytes());
                JsonObject entry = new JsonObject();
                entry.addProperty("url", dataUrl);
                refs.add(entry);
            }
        }
        if (refs.size() == 1) {
            body.add("image", refs.get(0));
        } else if (refs.size() > 1) {
            body.add("images", refs);
        }
        return body;
    }

    /**
     * Vertex AI Imagen {@code :predict} body: an {@code instances}/
     * {@code parameters} envelope. The instance carries the prompt and (for
     * editing) a single reference image; parameters carries {@code sampleCount}
     * + optional {@code aspectRatio}.
     */
    private JsonObject buildVertexBody(List<Part> parts) {
        JsonObject instance = new JsonObject();
        instance.addProperty("prompt", joinText(parts));
        for (Part part : parts) {
            if (part instanceof Part.ImagePart imagePart) {
                JsonObject image = new JsonObject();
                image.addProperty(
                        "bytesBase64Encoded", Base64.getEncoder().encodeToString(imagePart.media().bytes()));
                instance.add("image", image);
                break; // Vertex Imagen takes a single edit-target image
            }
        }
        if (options.mask != null) {
            JsonObject maskImage = new JsonObject();
            maskImage.addProperty("bytesBase64Encoded", Base64.getEncoder().encodeToString(options.mask.bytes()));
            JsonObject mask = new JsonObject();
            mask.add("image", maskImage);
            instance.add("mask", mask);
        }

        JsonObject parameters = new JsonObject();
        parameters.addProperty("sampleCount", options.count != null ? options.count : 1);
        if (options.aspectRatio != null) {
            parameters.addProperty("aspectRatio", options.aspectRatio);
        }
        if (options.safetyFilter != null) {
            parameters.addProperty("safetySetting", options.safetyFilter);
        }

        JsonArray instances = new JsonArray();
        instances.add(instance);

        JsonObject body = new JsonObject();
        body.add("instances", instances);
        body.add("parameters", parameters);
        return body;
    }

    // --- Response parsing (selected by responseShape, never provider name) ---

    private static ImageResponse parseResponse(JsonElement raw, ImageGenDef cfg) {
        return switch (cfg.responseShape()) {
            case "DataArrayB64Json" -> parseDataArray(raw, cfg.usageInputPath(), cfg.usageOutputPath());
            case "VertexPredictions" -> parseVertexResponse(raw);
            default -> parseGoogleParts(raw, cfg); // GoogleParts
        };
    }

    /**
     * OpenAI / xAI / Recraft {@code data[].b64_json} shape. SVG bytes (Recraft
     * vector models) are sniffed to {@code image/svg+xml} when the provider
     * echoes no mime.
     */
    private static ImageResponse parseDataArray(JsonElement raw, String inputPath, String outputPath) {
        List<ImageData> images = new ArrayList<>();
        List<String> revised = new ArrayList<>();
        JsonElement dataElement = Json.at(raw, "data");
        if (dataElement != null && dataElement.isJsonArray()) {
            for (JsonElement entryElement : dataElement.getAsJsonArray()) {
                if (!entryElement.isJsonObject()) {
                    continue;
                }
                JsonObject entry = entryElement.getAsJsonObject();
                JsonElement b64Element = entry.get("b64_json");
                if (b64Element != null && b64Element.isJsonPrimitive() && !b64Element.getAsString().isEmpty()) {
                    byte[] decoded = decodeBase64(b64Element.getAsString(), "image b64_json");
                    String mime = "image/png";
                    JsonElement mimeElement = entry.get("mime_type");
                    if (mimeElement != null && mimeElement.isJsonPrimitive() && !mimeElement.getAsString().isEmpty()) {
                        mime = mimeElement.getAsString();
                    }
                    // Vector providers (Recraft recraftv3_vector) return SVG
                    // bytes in the same b64_json slot without echoing a
                    // mime_type. Sniff the leading bytes so SVG is labeled
                    // image/svg+xml rather than the image/png default.
                    if ("image/png".equals(mime) && looksLikeSVG(decoded)) {
                        mime = "image/svg+xml";
                    }
                    images.add(new ImageData(mime, decoded));
                }
                JsonElement revisedElement = entry.get("revised_prompt");
                if (revisedElement != null
                        && revisedElement.isJsonPrimitive()
                        && !revisedElement.getAsString().isEmpty()) {
                    revised.add(revisedElement.getAsString());
                }
            }
        }
        Usage usage = new Usage(
                Json.optLong(raw, inputPath),
                Json.optLong(raw, outputPath),
                null, null, null, null);
        return new ImageResponse(images, String.join("\n", revised), usage, null, null, null);
    }

    /**
     * Vertex AI Imagen {@code :predict} shape: {@code {predictions:
     * [{bytesBase64Encoded, mimeType}]}}. Vertex reports no token counts, so
     * usage stays zero.
     */
    private static ImageResponse parseVertexResponse(JsonElement raw) {
        List<ImageData> images = new ArrayList<>();
        String finishReason = "";
        JsonElement predsElement = Json.at(raw, "predictions");
        if (predsElement != null && predsElement.isJsonArray()) {
            for (JsonElement entryElement : predsElement.getAsJsonArray()) {
                if (!entryElement.isJsonObject()) {
                    continue;
                }
                JsonObject entry = entryElement.getAsJsonObject();
                if (finishReason.isEmpty()) {
                    JsonElement raiElement = entry.get("raiFilteredReason");
                    if (raiElement != null && raiElement.isJsonPrimitive() && !raiElement.getAsString().isEmpty()) {
                        finishReason = raiElement.getAsString();
                    }
                }
                JsonElement b64Element = entry.get("bytesBase64Encoded");
                if (b64Element == null || !b64Element.isJsonPrimitive() || b64Element.getAsString().isEmpty()) {
                    continue;
                }
                byte[] decoded = decodeBase64(b64Element.getAsString(), "image bytesBase64Encoded");
                String mime = "image/png";
                JsonElement mimeElement = entry.get("mimeType");
                if (mimeElement != null && mimeElement.isJsonPrimitive() && !mimeElement.getAsString().isEmpty()) {
                    mime = mimeElement.getAsString();
                }
                images.add(new ImageData(mime, decoded));
            }
        }
        return new ImageResponse(images, "", Usage.none(), Json.optString(finishReason), null, null);
    }

    /** Google {@code candidates[].content.parts} inline-data shape. */
    private static ImageResponse parseGoogleParts(JsonElement raw, ImageGenDef cfg) {
        List<ImageData> images = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String finishReason = "";
        String finishMessage = "";
        JsonElement candidatesElement = Json.at(raw, "candidates");
        if (candidatesElement != null
                && candidatesElement.isJsonArray()
                && candidatesElement.getAsJsonArray().size() > 0) {
            JsonElement firstElement = candidatesElement.getAsJsonArray().get(0);
            if (firstElement.isJsonObject()) {
                JsonObject first = firstElement.getAsJsonObject();
                finishReason = Json.stringAt(first, "finishReason");
                finishMessage = Json.stringAt(first, "finishMessage");
                JsonElement partsElement = Json.at(first, "content.parts");
                if (partsElement != null && partsElement.isJsonArray()) {
                    for (JsonElement partElement : partsElement.getAsJsonArray()) {
                        if (!partElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject part = partElement.getAsJsonObject();
                        JsonElement inlineElement = part.get("inlineData");
                        if (inlineElement != null && inlineElement.isJsonObject()) {
                            JsonObject inline = inlineElement.getAsJsonObject();
                            JsonElement dataElement = inline.get("data");
                            if (dataElement != null
                                    && dataElement.isJsonPrimitive()
                                    && !dataElement.getAsString().isEmpty()) {
                                byte[] decoded = decodeBase64(dataElement.getAsString(), "image inlineData");
                                String mime = "";
                                JsonElement mimeElement = inline.get("mimeType");
                                if (mimeElement != null && mimeElement.isJsonPrimitive()) {
                                    mime = mimeElement.getAsString();
                                }
                                images.add(new ImageData(mime, decoded));
                            }
                        }
                        JsonElement textElement = part.get("text");
                        if (textElement != null
                                && textElement.isJsonPrimitive()
                                && !textElement.getAsString().isEmpty()) {
                            text.append(textElement.getAsString());
                        }
                    }
                }
            }
        }
        Usage usage = new Usage(
                cfg.usageInputPath().isEmpty() ? 0 : Json.longAt(raw, cfg.usageInputPath()),
                cfg.usageOutputPath().isEmpty() ? 0 : Json.longAt(raw, cfg.usageOutputPath()),
                null, null, null, null);
        return new ImageResponse(images, text.toString(), usage, Json.optString(finishReason), Json.optString(finishMessage), null);
    }

    /**
     * Whether the decoded bytes are an SVG document (XML prolog or {@code <svg}
     * root). Used to label vector-model output when the provider echoes no
     * mime type.
     */
    /** Decode provider-supplied base64, mapping malformed input into the error taxonomy. */
    private static byte[] decodeBase64(String value, String what) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new DecodingException("invalid base64 in " + what + ": " + e.getMessage(), e);
        }
    }

    private static boolean looksLikeSVG(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8).stripLeading();
        return text.startsWith("<?xml") || text.startsWith("<svg");
    }

    private static ImageModelDef findModel(ImageGenDef cfg, String modelId) {
        for (ImageModelDef m : cfg.models()) {
            if (m.modelId().equals(modelId)) {
                return m;
            }
        }
        return null;
    }
}
