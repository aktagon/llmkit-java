//

package com.aktagon.llmkit.providers.generated;

/**/
public final class TranscriptionGen {
    private TranscriptionGen() {}

    public static final class Def {
        public final String wireShape;
        /**/
        public final String interaction;
        /**/
        public final String requestEncoding;
        public final String submitEndpoint;
        public final String pollEndpoint;
        public final String uploadEndpoint;
        public final String submitHandleField;
        public final String statusPath;
        public final String doneStatus;
        public final String errorStatus;

        Def(
                String wireShape,
                String interaction,
                String requestEncoding,
                String submitEndpoint,
                String pollEndpoint,
                String uploadEndpoint,
                String submitHandleField,
                String statusPath,
                String doneStatus,
                String errorStatus) {
            this.wireShape = wireShape;
            this.interaction = interaction;
            this.requestEncoding = requestEncoding;
            this.submitEndpoint = submitEndpoint;
            this.pollEndpoint = pollEndpoint;
            this.uploadEndpoint = uploadEndpoint;
            this.submitHandleField = submitHandleField;
            this.statusPath = statusPath;
            this.doneStatus = doneStatus;
            this.errorStatus = errorStatus;
        }
    }

    public static Def config(ProviderName provider) {
        switch (provider) {
            case ASSEMBLYAI:
                return new Def(
                        "TranscriptionAssemblyAI",
                        "async",
                        "json",
                        "/v2/transcript",
                        "/v2/transcript/{id}",
                        "/v2/upload",
                        "id",
                        "status",
                        "completed",
                        "error");
            case OPENAI:
                return new Def(
                        "TranscriptionOpenAI",
                        "sync",
                        "multipart",
                        "/v1/audio/transcriptions",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "");
            default: return null;
        }
    }
}
