// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

public record ImageGenDef(
        String outputMode,
        /** Response wire family selecting the response parser (BUG-024). */
        String responseShape,
        String usageInputPath,
        String usageOutputPath,
        String usageReasoningPath,
        /** One request per carried input, TextOnly first (ADR-096). */
        java.util.List<RequestVariant> variants,
        java.util.List<ImageModelDef> models) {

    /** Public, keyless per-provider config; null when the provider has no image capability. */
    public static ImageGenDef imageGenConfig(ProviderName provider) {
        switch (provider) {
            case GOOGLE:
                return new ImageGenDef(
                        "Base64Inline",
                        "GoogleParts",
                        "usageMetadata.promptTokenCount",
                        "usageMetadata.candidatesTokenCount",
                        "usageMetadata.thoughtsTokenCount",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "InlineParts", ""), new RequestVariant(java.util.List.of("WithReferenceImages"), "InlineParts", "")),
                        java.util.List.of(
                                new ImageModelDef("gemini-3-pro-image-preview", "Nano Banana Pro", java.util.List.of("16:9", "1:1", "21:9", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16"), java.util.List.of("1K", "2K", "4K"), 0, true),
                                new ImageModelDef("gemini-3.1-flash-image-preview", "Nano Banana 2", java.util.List.of("16:9", "1:1", "1:4", "1:8", "21:9", "2:3", "3:2", "3:4", "4:1", "4:3", "4:5", "5:4", "8:1", "9:16"), java.util.List.of("1K", "2K", "4K", "512"), 0, true)
                        ));
            case GROK:
                return new ImageGenDef(
                        "Base64Inline",
                        "DataArrayB64Json",
                        "",
                        "",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "JSONInlineRefs", "/v1/images/generations"), new RequestVariant(java.util.List.of("WithReferenceImages"), "JSONInlineRefs", "/v1/images/edits")),
                        java.util.List.of(
                                new ImageModelDef("grok-imagine-image-quality", "Grok Imagine Quality", java.util.List.of("16:9", "19.5:9", "1:1", "1:2", "20:9", "2:1", "2:3", "3:2", "3:4", "4:3", "9:16", "9:19.5", "9:20", "auto"), java.util.List.of(), 0, true)
                        ));
            case OPENAI:
                return new ImageGenDef(
                        "Base64Inline",
                        "DataArrayB64Json",
                        "usage.input_tokens",
                        "usage.output_tokens",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "JSONGenerationsOpenAI", "/v1/images/generations"), new RequestVariant(java.util.List.of("WithReferenceImages"), "MultipartForm", "/v1/images/edits")),
                        java.util.List.of(
                                new ImageModelDef("gpt-image-1", "GPT Image 1", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("gpt-image-1-mini", "GPT Image 1 Mini", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("gpt-image-1.5", "GPT Image 1.5", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("gpt-image-2", "GPT Image 2", java.util.List.of(), java.util.List.of(), 0, true)
                        ));
            case OPENROUTER:
                return new ImageGenDef(
                        "Base64Inline",
                        "DataArrayB64Json",
                        "usage.prompt_tokens",
                        "usage.completion_tokens",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "JSONGenerations", "/v1/images"), new RequestVariant(java.util.List.of("WithReferenceImages"), "JSONGenerationsInputRefs", "/v1/images")),
                        java.util.List.of(
                                new ImageModelDef("bytedance-seed/seedream-4.5", "Seedream 4.5", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("bytedance-seed/seedream-5-0-flash", "Seedream 5.0 Flash", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("google/gemini-2.5-flash-image", "Nano Banana (Gemini 2.5 Flash Image)", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("google/gemini-3-pro-image", "Nano Banana Pro (Gemini 3 Pro Image)", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("google/gemini-3.1-flash-image", "Nano Banana 2 (Gemini 3.1 Flash Image)", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("google/gemini-3.1-flash-lite-image", "Nano Banana 2 Lite (Gemini 3.1 Flash Lite Image)", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("meta/muse-image", "Muse Image", java.util.List.of(), java.util.List.of(), 0, false),
                                new ImageModelDef("openai/gpt-5-image", "GPT-5 Image", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("openai/gpt-5-image-mini", "GPT-5 Image Mini", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("openai/gpt-5.4-image-2", "GPT-5.4 Image 2", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("openai/gpt-image-2", "GPT Image 2", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("openai/gpt-image-2.5-sunburst", "GPT Image 2.5 Sunburst", java.util.List.of(), java.util.List.of(), 0, true),
                                new ImageModelDef("tencent/hy-image-v3.5-preview", "Hy Image 3.5 Preview", java.util.List.of(), java.util.List.of(), 0, true)
                        ));
            case RECRAFT:
                return new ImageGenDef(
                        "Base64Inline",
                        "DataArrayB64Json",
                        "",
                        "",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "JSONGenerations", "/v1/images/generations")),
                        java.util.List.of(
                                new ImageModelDef("recraftv3", "Recraft V3", java.util.List.of(), java.util.List.of(), 0, false),
                                new ImageModelDef("recraftv3_vector", "Recraft V3 (vector / SVG)", java.util.List.of(), java.util.List.of(), 0, false)
                        ));
            default: return null;
        }
    }
}
