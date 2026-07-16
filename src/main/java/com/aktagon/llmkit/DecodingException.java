package com.aktagon.llmkit;


public final class DecodingException extends LlmKitException {
    public DecodingException(String message) {
        super("decoding: " + message);
    }
}
