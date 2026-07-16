package com.aktagon.llmkit;

/**
 * A user-supplied middleware hook (ADR-001). A non-null return in the pre
 * phase vetoes the operation (surfaced as {@link MiddlewareVetoException});
 * post-phase return values are discarded.
 */
@FunctionalInterface
public interface MiddlewareFn {
    RuntimeException apply(Event event);
}
