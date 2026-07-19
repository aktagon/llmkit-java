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

/*















*/
public final class Image {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;
    private final ImageOptions options;
    /**/
    private final List<MediaRef> inputImages;

    private Image(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            String model,
            ImageOptions options,
            List<MediaRef> inputImages) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
        this.options = options;
        this.inputImages = inputImages;
    }

    static Image root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Image(provider, apiKey, baseUrlOverride, http, null, new ImageOptions(), List.of());
    }

    /**/
    public Image model(String model) {
        return new Image(provider, apiKey, baseUrlOverride, http, model, options, inputImages);
    }

    /**/
    public Image aspectRatio(String value) {
        return withOptions(o -> o.aspectRatio = value);
    }

    /*


*/
    public Image imageSize(String value) {
        return withOptions(o -> o.imageSize = value);
    }

    /**/
    public Image includeText() {
        return withOptions(o -> o.includeText = true);
    }

    /**/
    public Image quality(String value) {
        return withOptions(o -> o.quality = value);
    }

    /**/
    public Image outputFormat(String value) {
        return withOptions(o -> o.outputFormat = value);
    }

    /**/
    public Image background(String value) {
        return withOptions(o -> o.background = value);
    }

    /**/
    public Image count(int value) {
        return withOptions(o -> o.count = value);
    }

    /*



*/
    public Image image(String mimeType, byte[] data) {
        List<MediaRef> images = new ArrayList<>(inputImages);
        images.add(new MediaRef(mimeType, data));
        return new Image(provider, apiKey, baseUrlOverride, http, model, options, List.copyOf(images));
    }

    /**/
    public Image addMiddleware(MiddlewareFn hook) {
        return withOptions(o -> o.middleware.add(hook));
    }

    /*



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

    /**/
    private Image withOptions(Consumer<ImageOptions> mutate) {
        ImageOptions copy = options.copy();
        mutate.accept(copy);
        return new Image(provider, apiKey, baseUrlOverride, http, model, copy, inputImages);
    }

    //

    /*



*/
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

    private static String joinText(List<Part> parts) {
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

        //
        //
        //
        if ("InlineParts".equals(imgCfg.inputMode())) {
            reject(options.quality, "quality", slug);
            reject(options.outputFormat, "output_format", slug);
            reject(options.background, "background", slug);
            reject(options.count, "count", slug);
        } else if ("JSONInlineRefs".equals(imgCfg.inputMode())) {
            reject(options.quality, "quality", slug);
            reject(options.outputFormat, "output_format", slug);
            reject(options.background, "background", slug);
        } else if ("JSONPredict".equals(imgCfg.inputMode())) {
            reject(options.quality, "quality", slug);
            reject(options.outputFormat, "output_format", slug);
            reject(options.background, "background", slug);
        } else if ("JSONGenerations".equals(imgCfg.inputMode())) {
            if (options.aspectRatio != null) {
                throw new ValidationException(
                        "aspect_ratio", "not supported by " + slug + "; use image_size (Recraft sizes by WxH)");
            }
            reject(options.quality, "quality", slug);
            reject(options.outputFormat, "output_format", slug);
            reject(options.background, "background", slug);
        }
        //
    }

    private static void reject(Object value, String field, String slug) {
        if (value != null) {
            throw new ValidationException(field, "not supported by " + slug);
        }
    }

    //

    private ImageResponse send(List<Part> parts, ImageGenDef imgCfg, Providers.Spec config) {
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);
        boolean hasImages = parts.stream().anyMatch(Image::isImage);

        String url;
        JsonObject body;
        if ("JSONInlineRefs".equals(imgCfg.inputMode())) {
            body = hasImages ? buildXAIEditBody(parts) : buildXAIGenBody(parts);
            url = base + (hasImages ? imgCfg.editEndpoint() : imgCfg.genEndpoint());
        } else if ("JSONGenerations".equals(imgCfg.inputMode())) {
            body = buildRecraftBody(parts);
            url = base + imgCfg.genEndpoint();
        } else if ("MultipartForm".equals(imgCfg.inputMode())) {
            if (hasImages) {
                throw new ValidationException(
                        "parts", "image editing (multipart/form-data) is not supported by the Java SDK (WIRE-008)");
            }
            body = buildOpenAIBody(parts);
            url = base + imgCfg.genEndpoint();
        } else if ("JSONPredict".equals(imgCfg.inputMode())) {
            body = buildVertexBody(parts);
            url = RequestBuilder.buildUrl(config, config.endpoint, apiKey, model, baseUrlOverride);
        } else { // InlineParts (Google generateContent)
            body = buildGoogleBody(parts);
            url = RequestBuilder.buildUrl(config, config.endpoint, apiKey, model, baseUrlOverride);
        }

        HttpTransport.Result result = http.postJson(url, Json.serialize(body), headers);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw ResponseParser.parseError(config, result.statusCode(), result.body());
        }
        JsonElement raw = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        //
        return parseResponse(raw, imgCfg);
    }

    //

    /*




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
        return body;
    }

    /*


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

    /*




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

    /*



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

    /*



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

    /*




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

        JsonObject parameters = new JsonObject();
        parameters.addProperty("sampleCount", options.count != null ? options.count : 1);
        if (options.aspectRatio != null) {
            parameters.addProperty("aspectRatio", options.aspectRatio);
        }

        JsonArray instances = new JsonArray();
        instances.add(instance);

        JsonObject body = new JsonObject();
        body.add("instances", instances);
        body.add("parameters", parameters);
        return body;
    }

    //

    private static ImageResponse parseResponse(JsonElement raw, ImageGenDef cfg) {
        return switch (cfg.responseShape()) {
            case "DataArrayB64Json" -> parseDataArray(raw, cfg.usageInputPath(), cfg.usageOutputPath());
            case "VertexPredictions" -> parseVertexResponse(raw);
            default -> parseGoogleParts(raw, cfg); // GoogleParts
        };
    }

    /*



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
                    //
                    //
                    //
                    //
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
                inputPath.isEmpty() ? 0 : Json.longAt(raw, inputPath),
                outputPath.isEmpty() ? 0 : Json.longAt(raw, outputPath),
                0, 0, 0, 0.0);
        return new ImageResponse(images, String.join("\n", revised), usage, "", "", null);
    }

    /*



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
        return new ImageResponse(images, "", Usage.zero(), finishReason, "", null);
    }

    /**/
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
                0, 0, 0, 0.0);
        return new ImageResponse(images, text.toString(), usage, finishReason, finishMessage, null);
    }

    /*



*/
    /**/
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
