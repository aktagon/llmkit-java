package com.aktagon.llmkit;

/**
 * Token consumption metrics for a generation call. Hand-written sibling of
 * the generated {@code Response} record (which references it by name),
 * mirroring Rust's {@code types.rs} {@code Usage} referenced from the
 * generated {@code structs.rs}. Each dimension is populated from the
 * provider-specific path declared per provider.
 *
 * <p>A dimension is either reported — carrying a value that may legitimately
 * be zero — or not reported at all, in which case the component is
 * {@code null}. The two are different claims: a provider that says it used no
 * cached tokens and a provider that never mentions caching are not the same
 * fact, and neither is a zero.
 *
 * <p>The components are boxed ({@code Long} / {@code Double}) rather than
 * {@code Optional}: {@code Optional} is documented as a return type, not a
 * field type — it is not {@code Serializable} and costs an allocation per
 * dimension — while a boxed reference type is Java's ordinary way of saying a
 * value may be absent (ADR-081 AVAIL-002).
 *
 * @param input tokens read from the prompt, or null if unreported
 * @param output tokens generated, or null if unreported
 * @param cacheWrite tokens written to a provider-side cache, or null if unreported
 * @param cacheRead tokens served from a provider-side cache, or null if unreported
 * @param reasoning tokens spent on hidden reasoning, or null if unreported
 * @param cost provider-reported cost in USD (ADR-027), or null if unreported —
 *     an unreported cost is not a free request
 */
public record Usage(
        Long input,
        Long output,
        Long cacheWrite,
        Long cacheRead,
        Long reasoning,
        Double cost) {

    /**
     * Usage with nothing reported, for providers that report no counts at all.
     * Distinct from a usage that reports zeroes: this one makes no claim.
     */
    public static Usage none() {
        return new Usage(null, null, null, null, null, null);
    }

    /**
     * Adds two usages dimension-wise, ABSORBING per dimension (ADR-081
     * AVAIL-005): a total is only reported when EVERY summand reported it.
     * Summing the turns that happened to report a dimension and presenting
     * that as the total is the original defect at aggregate scale.
     */
    public Usage plus(Usage other) {
        return new Usage(
                addLong(input, other.input),
                addLong(output, other.output),
                addLong(cacheWrite, other.cacheWrite),
                addLong(cacheRead, other.cacheRead),
                addLong(reasoning, other.reasoning),
                addDouble(cost, other.cost));
    }

    private static Long addLong(Long a, Long b) {
        return a == null || b == null ? null : a + b;
    }

    private static Double addDouble(Double a, Double b) {
        return a == null || b == null ? null : a + b;
    }
}
