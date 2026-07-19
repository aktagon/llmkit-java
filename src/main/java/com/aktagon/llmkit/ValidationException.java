package com.aktagon.llmkit;

/**/
public final class ValidationException extends LlmKitException {
    private final String field;

    public ValidationException(String field, String message) {
        super("validation: " + field + " - " + message);
        this.field = field;
    }

    /**/
    public String field() {
        return field;
    }
}
