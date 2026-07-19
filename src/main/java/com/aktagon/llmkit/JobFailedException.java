package com.aktagon.llmkit;

/**/
public final class JobFailedException extends LlmKitException {
    private final JobFailure cause;

    JobFailedException(String noun, JobFailure failure) {
        super(describe(noun, failure));
        this.cause = failure;
    }

    private static String describe(String noun, JobFailure failure) {
        String detail = failure.message().isEmpty() ? failure.status() : failure.message();
        return detail.isEmpty() ? noun + " failed" : noun + " failed: " + detail;
    }

    /**/
    public JobFailure failure() {
        return cause;
    }
}
