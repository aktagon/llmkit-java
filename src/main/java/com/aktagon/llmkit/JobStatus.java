package com.aktagon.llmkit;

/**
 * The normalized result of a single {@code poll} (ADR-063 POLL-001): the state
 * plus the result XOR the failure cause. {@code result} is non-null iff
 * {@code state == SUCCEEDED}; {@code cause} is non-null iff
 * {@code state == FAILED}. A record with accessor access
 * ({@code status.state()}) per the HANDOFF-036 B2 convention.
 */
public record JobStatus<T>(JobState state, T result, JobFailure cause, String rawStatus) {}
