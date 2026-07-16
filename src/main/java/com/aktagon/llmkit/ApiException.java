package com.aktagon.llmkit;

/** The provider returned a non-2xx status. */
public final class ApiException extends LlmKitException {
    private final String provider;
    private final int statusCode;

    public ApiException(String provider, int statusCode, String message) {
        super(provider + ": " + message + " (" + statusCode + ")");
        this.provider = provider;
        this.statusCode = statusCode;
    }

    /** The provider slug the failing call targeted. */
    public String provider() {
        return provider;
    }

    /** The HTTP status the provider returned. */
    public int statusCode() {
        return statusCode;
    }
}
