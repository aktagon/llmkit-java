package com.aktagon.llmkit;

/*





*/
public record JobStatus<T>(JobState state, T result, JobFailure cause, String rawStatus) {}
