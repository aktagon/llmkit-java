package com.aktagon.llmkit;

/** Base type for every error the library throws (unchecked). */
public abstract class LlmKitException extends RuntimeException {
    protected LlmKitException(String message) {
        super(message);
    }

    protected LlmKitException(String message, Throwable cause) {
        super(message, cause);
    }
}
