package com.aktagon.llmkit;

/**
 * The poll loop's wall-clock backstop fired before the provider reported a
 * terminal state (ADR-063 POLL-008). Distinguishable from a provider-reported
 * failure ({@link JobFailedException}) so callers can retry/resume the handle.
 */
public final class PollTimeoutException extends LlmKitException {
    private final String provider;
    private final String id;

    public PollTimeoutException(String provider, String id) {
        super("poll timeout: " + provider + " job " + id + " did not reach a terminal state");
        this.provider = provider;
        this.id = id;
    }

    /** The provider slug of the timed-out job. */
    public String provider() {
        return provider;
    }

    /** The provider-issued job id (persist + resume later). */
    public String id() {
        return id;
    }
}
