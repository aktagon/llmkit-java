package com.aktagon.llmkit;

/**
 * The HTTP transport failed before a response was produced.
 *
 * <p>Thread interruption is reported through this type too: when an HTTP call
 * or an inter-poll sleep catches {@link InterruptedException}, the SDK re-sets
 * the thread's interrupt flag and then throws a {@code TransportException}
 * carrying the original exception as its cause. Callers who need to separate
 * their own cancellation from a network failure should check
 * {@link Thread#isInterrupted()} or inspect {@link #getCause()}. A distinct
 * interruption subtype is deliberately deferred until a consumer needs to
 * catch it separately (HANDOFF-036 B5); adding one later is additive, since a
 * subclass still matches {@code catch (TransportException e)}.
 */
public final class TransportException extends LlmKitException {
    public TransportException(String message, Throwable cause) {
        super("transport: " + message, cause);
    }
}
