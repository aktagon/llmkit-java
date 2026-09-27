// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

/** Batch fact table (data only). */
public final class Batch {
    private Batch() {}

    // Batch contract constants shared by every SDK (ADR-091).

    /** Prefix + the request index is the id sent with each batch request. */
    public static final String BATCH_REQUEST_ID_PREFIX = "req-";
    /** finishReason of a batch slot whose request has no result line. */
    public static final String BATCH_SLOT_MISSING = "missing";
    /** finishReason of a failed batch slot when the provider gives no reason. */
    public static final String BATCH_SLOT_ERROR = "error";

    public enum InputMode { INLINE_REQUESTS, FILE_REFERENCE_INPUT }

    public static final class Def {
        public final InputMode inputMode;
        public final String inputField;
        public final String filePurpose;
        public final String requestWrapper;
        public final String completionWindow;
        public final String endpointPath;
        public final String itemBodyField;
        public final String resultBodyPath;
        public final String resultKeyPath;
        public final String resultStatusPath;
        public final java.util.List<String> resultSuccessValues;
        public final java.util.List<String> resultReasonPaths;
        public final java.util.List<String> resultMessagePaths;
        public final java.util.List<String> requestCountPaths;
        /** Nullable. */
        public final Caching.ResourceLifecycleDef lifecycle;

        Def(
                InputMode inputMode,
                String inputField,
                String filePurpose,
                String requestWrapper,
                String completionWindow,
                String endpointPath,
                String itemBodyField,
                String resultBodyPath,
                String resultKeyPath,
                String resultStatusPath,
                java.util.List<String> resultSuccessValues,
                java.util.List<String> resultReasonPaths,
                java.util.List<String> resultMessagePaths,
                java.util.List<String> requestCountPaths,
                Caching.ResourceLifecycleDef lifecycle) {
            this.inputMode = inputMode;
            this.inputField = inputField;
            this.filePurpose = filePurpose;
            this.requestWrapper = requestWrapper;
            this.completionWindow = completionWindow;
            this.endpointPath = endpointPath;
            this.itemBodyField = itemBodyField;
            this.resultBodyPath = resultBodyPath;
            this.resultKeyPath = resultKeyPath;
            this.resultStatusPath = resultStatusPath;
            this.resultSuccessValues = resultSuccessValues;
            this.resultReasonPaths = resultReasonPaths;
            this.resultMessagePaths = resultMessagePaths;
            this.requestCountPaths = requestCountPaths;
            this.lifecycle = lifecycle;
        }
    }

    public static Def config(ProviderName provider) {
        switch (provider) {
            case ANTHROPIC:
                return new Def(
                        InputMode.INLINE_REQUESTS,
                        "",
                        "",
                        "requests",
                        "",
                        "",
                        "params",
                        "result.message",
                        "custom_id",
                        "result.type",
                        java.util.List.of("succeeded"),
                        java.util.List.of("result.type"),
                        java.util.List.of("result.error.error.message"),
                        java.util.List.of("request_counts.processing", "request_counts.succeeded", "request_counts.errored", "request_counts.canceled", "request_counts.expired"),
                        new Caching.ResourceLifecycleDef(
                                "/v1/messages/batches",
                                "id",
                                "",
                                "",
                                "processing_status",
                                "ended",
                                java.util.List.of(),
                                "/v1/messages/batches/{id}/results",
                                "",
                                "",
                                "",
                                ""));
            case GOOGLE:
                return new Def(
                        InputMode.INLINE_REQUESTS,
                        "",
                        "",
                        "requests",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        null);
            case OPENAI:
                return new Def(
                        InputMode.FILE_REFERENCE_INPUT,
                        "input_file_id",
                        "batch",
                        "",
                        "24h",
                        "/v1/chat/completions",
                        "",
                        "response.body",
                        "custom_id",
                        "response.status_code",
                        java.util.List.of("200"),
                        java.util.List.of("error.code", "response.body.error.code"),
                        java.util.List.of("error.message", "response.body.error.message"),
                        java.util.List.of("request_counts.total"),
                        new Caching.ResourceLifecycleDef(
                                "/v1/batches",
                                "id",
                                "",
                                "",
                                "status",
                                "completed",
                                java.util.List.of("failed", "expired", "cancelled"),
                                "",
                                "",
                                "output_file_id",
                                "error_file_id",
                                "/v1/files/{id}/content"));
            default: return null;
        }
    }
}
