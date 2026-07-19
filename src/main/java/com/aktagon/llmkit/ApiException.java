package com.aktagon.llmkit;

/**/
public final class ApiException extends LlmKitException {
    private final String provider;
    private final int statusCode;

    public ApiException(String provider, int statusCode, String message) {
        super(provider + ": " + message + " (" + statusCode + ")");
        this.provider = provider;
        this.statusCode = statusCode;
    }

    /**/
    public String provider() {
        return provider;
    }

    /**/
    public int statusCode() {
        return statusCode;
    }
}
