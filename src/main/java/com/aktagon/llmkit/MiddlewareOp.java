package com.aktagon.llmkit;

/**
 * The operation an {@link Event} describes (ADR-001). Mirrors the generated
 * {@code MiddlewareOp} in the other five SDKs (Go {@code providers.OpLLMRequest},
 * Rust {@code MiddlewareOp::LlmRequest}, Swift {@code .llmRequest}); Java
 * hand-writes it alongside the runtime rather than emitting it, exactly as
 * Swift's Phase 4a did (no generated middleware constants file yet, note #16).
 */
public enum MiddlewareOp {
    LLM_REQUEST("llm_request"),
    TOOL_CALL("tool_call"),
    CACHE_CREATE("cache_create"),
    UPLOAD("upload"),
    BATCH_SUBMIT("batch_submit"),
    IMAGE_GENERATION("image_generation"),
    MUSIC_GENERATION("music_generation"),
    VIDEO_GENERATION("video_generation"),
    MODELS_LIST("models_list");

    private final String label;

    MiddlewareOp(String label) {
        this.label = label;
    }

    /** The canonical op label used across every SDK (e.g. {@code "llm_request"}). */
    public String label() {
        return label;
    }
}
