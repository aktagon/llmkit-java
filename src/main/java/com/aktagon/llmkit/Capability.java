package com.aktagon.llmkit;

/**
 * The capabilities a model can expose. {@code Capability} instances in
 *
 *
 * Hand-written sibling of the generated struct surface (mirrors Swift's
 * {@code Capability.swift} / Rust's {@code types.rs} {@code Capability}).
 */
public enum Capability {
    CHAT_COMPLETION,
    IMAGE_GENERATION,
    TOOL_CALLING,
    FILE_UPLOAD,
    BATCHING,
    CACHING,
    REASONING,
    CATALOGUE
}
