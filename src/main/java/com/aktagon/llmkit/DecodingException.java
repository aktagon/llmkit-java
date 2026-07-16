package com.aktagon.llmkit;

/** The response body could not be decoded. */
public final class DecodingException extends LlmKitException {
    public DecodingException(String message) {
        super("decoding: " + message);
    }
}
