package com.aktagon.llmkit;

/** Which side of an operation an {@link Event} describes (ADR-001). */
public enum MiddlewarePhase {
    /** Fires before the operation; a non-null {@link MiddlewareFn} return aborts it. */
    PRE,
    /** Fires after the operation completes or fails; return value is discarded. */
    POST
}
