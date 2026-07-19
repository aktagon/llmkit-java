package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import java.util.List;

/*




*/
final class Job {
    private Job() {}

    /**/
    static final class LifecycleConfig {
        String noun;
        String provider;
        String id;
        String statusPath;
        List<String> doneValues;
        List<String> errorValues;
        String errorMessagePath;
        /**/
        long pollIntervalMillis;
        /**/
        long pollTimeoutMillis;
    }

    /**/
    record PollBody(JsonElement raw) {
        String status(String path) {
            return Json.stringAt(raw, path);
        }
    }

    /**/
    record Classification(JobState state, JobFailure failure, String rawStatus) {}

    /*



*/
    interface Adapter<T> {
        LifecycleConfig config();

        PollBody poll();

        Classification classify(PollBody body);

        T result(PollBody body);
    }

    /*



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

    /*


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

    /*



*/
    static <T> T pollJob(Adapter<T> adapter) {
        LifecycleConfig lc = adapter.config();
        long interval = lc.pollIntervalMillis > 0 ? lc.pollIntervalMillis : 2_000;
        long deadline = lc.pollTimeoutMillis > 0 ? System.nanoTime() + lc.pollTimeoutMillis * 1_000_000 : 0;
        while (true) {
            JobStatus<T> status = pollOnce(adapter);
            switch (status.state()) {
                case SUCCEEDED -> {
                    //
                    //
                    if (status.result() == null) {
                        throw new IllegalStateException(lc.noun + ": succeeded status carried no result");
                    }
                    return status.result();
                }
                case FAILED -> throw new JobFailedException(
                        lc.noun, status.cause() != null ? status.cause() : new JobFailure("", "", false));
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

    /*


*/
    static List<String> nonEmptyValues(List<String> values) {
        return values.stream().filter(v -> !v.isEmpty()).toList();
    }
}
