package com.aktagon.llmkit;

/*




*/
public enum JobState {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String label;

    JobState(String label) {
        this.label = label;
    }

    /**/
    public String label() {
        return label;
    }
}
