package com.aktagon.llmkit;

/**
 * The lifecycle state of an async job (ADR-062 / ADR-063). Public because it
 * is what {@code poll} returns (POLL-004). Monotonic —
 * {@code RUNNING -> (SUCCEEDED | FAILED)}. No unknown/zero member by design
 * (ADR-063 refinements 2).
 */
public enum JobState {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String label;

    JobState(String label) {
        this.label = label;
    }

    /** The cross-SDK wire label ("running" / "succeeded" / "failed"). */
    public String label() {
        return label;
    }
}
