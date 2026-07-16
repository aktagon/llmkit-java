package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import java.util.List;

/**
 * The shared async-job poll engine (ADR-062 slice 1) — a port of Swift's
 * {@code Job} / {@code go/job.go}. One engine owns the poll loop, deadline,
 * and the monotonic {@code RUNNING -> (SUCCEEDED | FAILED)} state machine
 * behind four adapter seams (config / poll / classify / result).
 */
final class Job {
    private Job() {}

    /** The config half of the engine seam: classification facts + poll cadence. */
    static final class LifecycleConfig {
        String noun;
        String provider;
        String id;
        String statusPath;
        List<String> doneValues;
        List<String> errorValues;
        String errorMessagePath;
        /** Cadence between polls, in milliseconds. Zero = default (2s). */
        long pollIntervalMillis;
        /** Wall-clock backstop for the poll LOOP, in milliseconds. Zero = none. */
        long pollTimeoutMillis;
    }

    /** The once-decoded provider poll response. Confines the untyped JSON leaf. */
    record PollBody(JsonElement raw) {
        String status(String path) {
            return Json.stringAt(raw, path);
        }
    }

    /** What {@code classify} returns: the state plus the failure detail when FAILED. */
    record Classification(JobState state, JobFailure failure, String rawStatus) {}

    /**
     * The capability seams the engine cannot share (ADR-062 difference table).
     * {@code result} may perform a second network hop (batch's output_file_id
     * to a file-content GET).
     */
    interface Adapter<T> {
        LifecycleConfig config();

        PollBody poll();

        Classification classify(PollBody body);

        T result(PollBody body);
    }

    /**
     * The shared config-driven default classifier (ADR-062). Precedence
     * done &gt; error &gt; running: an unmodeled status stays RUNNING (bounded
     * by the backstop), never a false terminal.
     */
    static Classification classifyByConfig(LifecycleConfig lc, PollBody body) {
        String status = body.status(lc.statusPath);
        if (lc.doneValues.contains(status)) {
            return new Classification(JobState.SUCCEEDED, null, status);
        }
        if (lc.errorValues.contains(status)) {
            String message = lc.errorMessagePath.isEmpty() ? "" : body.status(lc.errorMessagePath);
            return new Classification(JobState.FAILED, new JobFailure(status, message, false), status);
        }
        return new Classification(JobState.RUNNING, null, status);
    }

    /**
     * One engine iteration: poll, classify, and on success the capability
     * result tail. This IS {@code poll()} made public; no loop, no deadline.
     */
    static <T> JobStatus<T> pollOnce(Adapter<T> adapter) {
        PollBody body = adapter.poll();
        Classification classification = adapter.classify(body);
        return switch (classification.state()) {
            case SUCCEEDED -> new JobStatus<>(
                    JobState.SUCCEEDED, adapter.result(body), null, classification.rawStatus());
            case FAILED -> new JobStatus<>(
                    JobState.FAILED, null, classification.failure(), classification.rawStatus());
            case RUNNING -> new JobStatus<>(JobState.RUNNING, null, null, classification.rawStatus());
        };
    }

    /**
     * The shared engine (ADR-062). Loops {@code pollOnce} on the configured
     * cadence until the first terminal classification or the deadline backstop
     * (surfaced as the typed {@link PollTimeoutException}, POLL-008).
     */
    static <T> T pollJob(Adapter<T> adapter) {
        LifecycleConfig lc = adapter.config();
        long interval = lc.pollIntervalMillis > 0 ? lc.pollIntervalMillis : 2_000;
        long deadline = lc.pollTimeoutMillis > 0 ? System.nanoTime() + lc.pollTimeoutMillis * 1_000_000 : 0;
        while (true) {
            JobStatus<T> status = pollOnce(adapter);
            switch (status.state) {
                case SUCCEEDED -> {
                    // pollOnce sets result iff SUCCEEDED (by construction);
                    // guard the invariant rather than assume it.
                    if (status.result == null) {
                        throw new IllegalStateException(lc.noun + ": succeeded status carried no result");
                    }
                    return status.result;
                }
                case FAILED -> throw new JobFailedException(
                        lc.noun, status.cause != null ? status.cause : new JobFailure("", "", false));
                case RUNNING -> { }
            }
            if (deadline != 0 && System.nanoTime() > deadline) {
                throw new PollTimeoutException(lc.provider, lc.id);
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransportException("interrupted while polling", e);
            }
        }
    }

    /**
     * Filters out empty strings so a provider that leaves a status value unset
     * contributes an empty set rather than a value matching a missing status.
     */
    static List<String> nonEmptyValues(List<String> values) {
        return values.stream().filter(v -> !v.isEmpty()).toList();
    }
}
