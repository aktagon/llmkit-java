package com.aktagon.llmkit;

/** A request, option, or builder field failed pre-flight validation. */
public final class ValidationException extends LlmKitException {
    private final String field;

    public ValidationException(String field, String message) {
        super("validation: " + field + " - " + message);
        this.field = field;
    }

    /** The offending field name (e.g. {@code "model"}). */
    public String field() {
        return field;
    }
}
