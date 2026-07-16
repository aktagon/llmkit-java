package com.aktagon.llmkit;

/**
 * The normalized result of a single {@code poll} (ADR-063 POLL-001): the state
 * plus the result XOR the failure cause. {@code result} is non-null iff
 * {@code state == SUCCEEDED}; {@code cause} is non-null iff
 * {@code state == FAILED}.
 */
public final class JobStatus<T> {
    public final JobState state;
    public final T result;
    public final JobFailure cause;
    public final String rawStatus;

    JobStatus(JobState state, T result, JobFailure cause, String rawStatus) {
        this.state = state;
        this.result = result;
        this.cause = cause;
        this.rawStatus = rawStatus;
    }
}
