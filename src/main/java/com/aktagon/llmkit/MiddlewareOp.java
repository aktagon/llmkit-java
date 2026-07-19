package com.aktagon.llmkit;

/*





*/
public enum MiddlewareOp {
    LLM_REQUEST("llm_request"),
    TOOL_CALL("tool_call"),
    CACHE_CREATE("cache_create"),
    UPLOAD("upload"),
    BATCH_SUBMIT("batch_submit"),
    IMAGE_GENERATION("image_generation"),
    MUSIC_GENERATION("music_generation"),
    VIDEO_GENERATION("video_generation"),
    MODELS_LIST("models_list");

    private final String label;

    MiddlewareOp(String label) {
        this.label = label;
    }

    /**/
    public String label() {
        return label;
    }
}
