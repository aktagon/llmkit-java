package com.aktagon.llmkit;

/**
 * Token consumption metrics for a generation call. Hand-written sibling of
 * the generated {@code Response} record (which references it by name),
 * mirroring Rust's {@code types.rs} {@code Usage} referenced from the
 * generated {@code structs.rs}. Each dimension is populated from the
 * provider-specific path declared per provider.
 *
 * @param input tokens read from the prompt
 * @param output tokens generated
 * @param cacheWrite tokens written to a provider-side cache
 * @param cacheRead tokens served from a provider-side cache
 * @param reasoning tokens spent on hidden reasoning
 * @param cost provider-reported cost (USD); 0.0 when unreported (ADR-027)
 */
public record Usage(
        long input,
        long output,
        long cacheWrite,
        long cacheRead,
        long reasoning,
        double cost) {

    /** All-zero usage, for providers that report nothing. */
    public static Usage zero() {
        return new Usage(0, 0, 0, 0, 0, 0.0);
    }
}
