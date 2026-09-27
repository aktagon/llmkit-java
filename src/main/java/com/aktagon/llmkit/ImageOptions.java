package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.MediaRef;
import java.util.ArrayList;
import java.util.List;

/**
 * The accumulated image-generation parameters carried by the {@link Image}
 * builder (mirrors Swift's {@code ImageOptions} / Rust's {@code ImageOptions}).
 * Package-private — the public surface is the builder chain. Mutable;
 * {@link Image} clones it on each chain step so builders stay immutable
 * value-like.
 */
final class ImageOptions {
    String aspectRatio;
    String imageSize;
    boolean includeText = false;
    /** OpenAI gpt-image-* quality enum (low|medium|high|auto). */
    String quality;
    /** OpenAI gpt-image-* output MIME format (png|webp|jpeg). */
    String outputFormat;
    /** OpenAI gpt-image-* background treatment (transparent|opaque|auto). */
    String background;
    /** Number of images to generate; wire field {@code n} (OpenAI + Recraft + xAI). */
    Integer count;
    /** Observation + veto hooks fired around the {@code imageGeneration} op. */
    List<MiddlewareFn> middleware = new ArrayList<>();
    /** Inpainting mask (OpenAI edits multipart {@code mask}; Vertex {@code instances[0].mask}). */
    MediaRef mask;
    /** Vertex Imagen global safety threshold; wire field {@code parameters.safetySetting}. */
    String safetyFilter;
    /** Google per-category safety thresholds; wire field {@code safetySettings[]}. */
    List<SafetySetting> safetySettings = new ArrayList<>();
    /** Attach the provider's response body to the ImageResponse (ADR-014 raw opt-in). */
    boolean raw = false;

    ImageOptions() {}

    /** A deep-enough copy for clone-on-chain (lists copied, scalars shared). */
    ImageOptions copy() {
        ImageOptions o = new ImageOptions();
        o.aspectRatio = aspectRatio;
        o.imageSize = imageSize;
        o.includeText = includeText;
        o.quality = quality;
        o.outputFormat = outputFormat;
        o.background = background;
        o.count = count;
        o.middleware = new ArrayList<>(middleware);
        o.mask = mask;
        o.safetyFilter = safetyFilter;
        o.safetySettings = new ArrayList<>(safetySettings);
        o.raw = raw;
        return o;
    }
}
