package com.aktagon.llmkit;

/*



*/
public final class PollTimeoutException extends LlmKitException {
    private final String provider;
    private final String id;

    public PollTimeoutException(String provider, String id) {
        super("poll timeout: " + provider + " job " + id + " did not reach a terminal state");
        this.provider = provider;
        this.id = id;
    }

    /**/
    public String provider() {
        return provider;
    }

    /**/
    public String id() {
        return id;
    }
}
