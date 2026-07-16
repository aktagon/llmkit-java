package com.aktagon.llmkit;

/** The HTTP transport failed before a response was produced. */
public final class TransportException extends LlmKitException {
    public TransportException(String message, Throwable cause) {
        super("transport: " + message, cause);
    }
}
