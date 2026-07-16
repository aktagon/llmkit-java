package com.aktagon.llmkit;

/**
 * A per-request safety-setting pair (Google {@code safetySettings}). Public
 * because it is a builder argument ({@code Text.safetySettings(...)}); mirrors
 * Swift's {@code SafetySetting}.
 *
 * @param category the harm category (e.g. {@code "HARM_CATEGORY_DANGEROUS_CONTENT"})
 * @param threshold the block threshold (e.g. {@code "BLOCK_ONLY_HIGH"})
 */
public record SafetySetting(String category, String threshold) {}
