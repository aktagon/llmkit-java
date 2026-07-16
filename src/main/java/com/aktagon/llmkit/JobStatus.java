package com.aktagon.llmkit;







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
