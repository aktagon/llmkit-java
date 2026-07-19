package com.aktagon.llmkit;

/*











*/
public final class TransportException extends LlmKitException {
    public TransportException(String message, Throwable cause) {
        super("transport: " + message, cause);
    }
}
