package com.aktagon.llmkit;

import java.util.List;

/**
 * Middleware runtime — pre-phase veto + post-phase observation (ADR-001), a
 * port of Rust's {@code middleware.rs} / Swift's {@code Middleware.swift}.
 * The handwritten capability runtimes (Text, Agent, Batching, CachingRuntime)
 * are responsible for firing middleware around each operation site.
 */
final class Middleware {
    private Middleware() {}

    /**
     * Run pre-phase hooks in registration order. The first non-null return
     * aborts the operation and is thrown as {@link MiddlewareVetoException}.
     */
    static void firePre(List<MiddlewareFn> hooks, Event event) {
        for (MiddlewareFn hook : hooks) {
            RuntimeException veto = hook.apply(event);
            if (veto != null) {
                throw new MiddlewareVetoException(veto);
            }
        }
    }

    /**
     * Run post-phase hooks in registration order. Return values are
     * discarded — post-phase is strictly observational.
     */
    static void firePost(List<MiddlewareFn> hooks, Event event) {
        for (MiddlewareFn hook : hooks) {
            hook.apply(event);
        }
    }

    /** Wall-clock elapsed time since {@code startNanos} ({@link System#nanoTime()}), in millis. */
    static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
