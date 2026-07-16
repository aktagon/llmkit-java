package com.aktagon.llmkit;

/**
 * The normalized failure detail carried by a FAILED status (ADR-062): the raw
 * provider status, an optional provider error message, and a timed-out flag.
 * A typed cause enum is a non-breaking follow-up (ADR-062 slice 2).
 */
public record JobFailure(String status, String message, boolean timedOut) {}
