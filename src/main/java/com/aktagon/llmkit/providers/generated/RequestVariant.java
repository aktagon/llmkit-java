// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

/** One wire request of an image or video config, keyed by the inputs it
 * carries (ADR-096). {@code shape} is empty on a video variant; an empty
 * {@code endpoint} means the provider's main endpoint template. {@code carries}
 * lists the carried inputs; a first-and-last-frame variant carries two. */
public record RequestVariant(java.util.List<String> carries, String shape, String endpoint) {
    /** Carried input: text only. */
    public static final String TEXT_ONLY = "TextOnly";
    /** Carried input: one or more reference images. */
    public static final String WITH_REFERENCE_IMAGES = "WithReferenceImages";
    /** Carried input: a video seed frame. */
    public static final String WITH_SEED_FRAME = "WithSeedFrame";
    /** Carried input: a video last frame (only with a seed frame). */
    public static final String WITH_LAST_FRAME = "WithLastFrame";

    // Image request shapes: each names one request body.
    public static final String INLINE_PARTS = "InlineParts";
    public static final String JSON_GENERATIONS_OPENAI = "JSONGenerationsOpenAI";
    public static final String MULTIPART_FORM = "MultipartForm";
    public static final String JSON_INLINE_REFS = "JSONInlineRefs";
    /** Deprecated: no provider accepts this since 2026-10-15 (Vertex Imagen shut down, BUG-101); removed at the next major. */
    public static final String JSON_PREDICT = "JSONPredict";
    public static final String JSON_GENERATIONS = "JSONGenerations";
}
