package com.aktagon.llmkit;

/**/
public abstract class LlmKitException extends RuntimeException {
    protected LlmKitException(String message) {
        super(message);
    }

    protected LlmKitException(String message, Throwable cause) {
        super(message, cause);
    }
}
