package com.aktagon.llmkit;

/**
 * The capabilities a model can expose. {@code llm:Capability} instances in
 * the ontology; {@code ModelInfo.capabilities} is a list of these.
 * Ontology-derived per ADR-019 — never populated from provider wire data.
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
