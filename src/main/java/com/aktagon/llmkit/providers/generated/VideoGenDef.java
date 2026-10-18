// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

public record VideoGenDef(
        String wireShape,
        /** One of download | url | output-uri. */
        String outputDelivery,
        /** Video API base when it differs from the chat base; "" = use chat base. */
        String videoBaseUrl,
        /** Submit requests per carried input, TextOnly first; each endpoint is
         * relative to the resolved video base (ADR-096). */
        java.util.List<RequestVariant> variants,
        /** Poll template with an {id} placeholder. */
        String pollEndpoint,
        /** File-retrieve template with {file_id} for two-hop providers; "" = single-hop. */
        String fileEndpoint,
        /** Cancel template with {model} and {operation_id}; "" = no documented cancel. */
        String cancelEndpoint,
        String submitHandleField,
        boolean requiresOutputUri,
        java.util.List<VideoModelDef> models) {

    /** Public, keyless per-provider config; null when the provider has no video capability. */
    public static VideoGenDef videoGenConfig(ProviderName provider) {
        switch (provider) {
            case BEDROCK:
                return new VideoGenDef(
                        "VideoBedrock",
                        "DeliveryOutputURI",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/async-invoke")),
                        "/async-invoke/{id}",
                        "",
                        "",
                        "invocationArn",
                        true,
                        java.util.List.of(
                                new VideoModelDef("amazon.nova-reel-v1:0", "Nova Reel", false, false, false, 6, "video/mp4", java.util.List.of("720p"), 0)
                        ));
            case GOOGLE:
                return new VideoGenDef(
                        "VideoVeo",
                        "DeliveryDownload",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/v1beta/models/{model}:predictLongRunning"), new RequestVariant(java.util.List.of("WithReferenceImages"), "", "/v1beta/models/{model}:predictLongRunning"), new RequestVariant(java.util.List.of("WithSeedFrame"), "", "/v1beta/models/{model}:predictLongRunning"), new RequestVariant(java.util.List.of("WithSeedFrame", "WithLastFrame"), "", "/v1beta/models/{model}:predictLongRunning")),
                        "/v1beta/{id}",
                        "",
                        "",
                        "name",
                        false,
                        java.util.List.of(
                                new VideoModelDef("veo-3.1-fast-generate-preview", "Veo 3.1 Fast", true, true, true, 8, "video/mp4", java.util.List.of("1080p", "4k", "720p"), 1),
                                new VideoModelDef("veo-3.1-generate-preview", "Veo 3.1", true, true, true, 8, "video/mp4", java.util.List.of("1080p", "720p"), 1),
                                new VideoModelDef("veo-3.1-lite-generate-preview", "Veo 3.1 Lite", true, true, false, 8, "video/mp4", java.util.List.of("1080p", "720p"), 1)
                        ));
            case GROK:
                return new VideoGenDef(
                        "VideoGrok",
                        "DeliveryURL",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/v1/videos/generations"), new RequestVariant(java.util.List.of("WithSeedFrame"), "", "/v1/videos/generations")),
                        "/v1/videos/{id}",
                        "",
                        "",
                        "request_id",
                        false,
                        java.util.List.of(
                                new VideoModelDef("grok-imagine-video", "Grok Imagine Video", true, false, false, 15, "video/mp4", java.util.List.of("480p", "720p"), 1)
                        ));
            case MINIMAX:
                return new VideoGenDef(
                        "VideoMinimax",
                        "DeliveryURL",
                        "https://api.minimax.io",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/v1/video_generation")),
                        "/v1/query/video_generation?task_id={id}",
                        "/v1/files/retrieve?file_id={file_id}",
                        "",
                        "task_id",
                        false,
                        java.util.List.of(
                                new VideoModelDef("MiniMax-Hailuo-2.3", "MiniMax Hailuo 2.3", false, false, false, 6, "video/mp4", java.util.List.of("1080p", "768p"), 0)
                        ));
            case OPENROUTER:
                return new VideoGenDef(
                        "VideoOpenRouter",
                        "DeliveryDownload",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/v1/videos")),
                        "/v1/videos/{id}",
                        "",
                        "",
                        "id",
                        false,
                        java.util.List.of(
                                new VideoModelDef("alibaba/wan-3.0", "Wan 3.0", false, false, false, 0, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("bytedance/seedance-2.0", "Seedance 2.0", false, false, false, 0, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("bytedance/seedance-2.0-fast", "Seedance 2.0 Fast", false, false, false, 0, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("bytedance/seedance-2.5", "Seedance 2.5", false, false, false, 0, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("google/veo-3.1-fast", "Veo 3.1 Fast", false, false, false, 0, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("google/veo-3.1-lite", "Veo 3.1 Lite", false, false, false, 0, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("heygen/heygen-video-1", "HeyGen Video 1", false, false, false, 0, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("kwaivgi/kling-v3.0-std", "Kling 3.0 Standard", false, false, false, 0, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("minimax/hailuo-3", "Hailuo 3", false, false, false, 0, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("x-ai/grok-imagine-video-1.5-lite", "Grok Imagine Video 1.5 Lite", false, false, false, 0, "video/mp4", java.util.List.of(), 0)
                        ));
            case PIXVERSE:
                return new VideoGenDef(
                        "VideoPixVerse",
                        "DeliveryURL",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/openapi/v2/video/text/generate")),
                        "/openapi/v2/video/result/{id}",
                        "",
                        "",
                        "Resp.video_id",
                        false,
                        java.util.List.of(
                                new VideoModelDef("v4.5", "PixVerse v4.5", false, false, false, 8, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("v5", "PixVerse v5", false, false, false, 8, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("v6", "PixVerse v6", false, false, false, 15, "video/mp4", java.util.List.of(), 0)
                        ));
            case QWEN:
                return new VideoGenDef(
                        "VideoQwen",
                        "DeliveryURL",
                        "https://dashscope-intl.aliyuncs.com",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/api/v1/services/aigc/video-generation/video-synthesis")),
                        "/api/v1/tasks/{id}",
                        "",
                        "",
                        "output.task_id",
                        false,
                        java.util.List.of(
                                new VideoModelDef("wan2.2-t2v-plus", "Wan 2.2 T2V Plus", false, false, false, 5, "video/mp4", java.util.List.of("720p"), 0)
                        ));
            case TOGETHER:
                return new VideoGenDef(
                        "VideoTogether",
                        "DeliveryURL",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/v2/videos")),
                        "/v2/videos/{id}",
                        "",
                        "",
                        "id",
                        false,
                        java.util.List.of(
                                new VideoModelDef("minimax/video-01-director", "MiniMax Video 01 Director (Together)", false, false, false, 6, "video/mp4", java.util.List.of("720p"), 0)
                        ));
            case VERTEX:
                return new VideoGenDef(
                        "VideoVertexVeo",
                        "DeliveryDownload",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/{model}:predictLongRunning"), new RequestVariant(java.util.List.of("WithReferenceImages"), "", "/{model}:predictLongRunning"), new RequestVariant(java.util.List.of("WithSeedFrame"), "", "/{model}:predictLongRunning"), new RequestVariant(java.util.List.of("WithSeedFrame", "WithLastFrame"), "", "/{model}:predictLongRunning")),
                        "/{model}:fetchPredictOperation",
                        "",
                        "/{model}/operations/{operation_id}:cancel",
                        "name",
                        false,
                        java.util.List.of(
                                new VideoModelDef("veo-3.1-fast-generate-001", "Veo 3.1 Fast", true, true, true, 8, "video/mp4", java.util.List.of("1080p", "720p"), 1),
                                new VideoModelDef("veo-3.1-generate-001", "Veo 3.1", true, true, true, 8, "video/mp4", java.util.List.of("1080p", "4k", "720p"), 1),
                                new VideoModelDef("veo-3.1-generate-preview", "Veo 3.1", true, false, true, 8, "video/mp4", java.util.List.of("1080p", "720p"), 1),
                                new VideoModelDef("veo-3.1-lite-generate-001", "Veo 3.1 Lite", false, false, false, 8, "video/mp4", java.util.List.of("1080p", "720p"), 0)
                        ));
            case VIDU:
                return new VideoGenDef(
                        "VideoVidu",
                        "DeliveryURL",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/ent/v2/text2video")),
                        "/ent/v2/tasks/{id}/creations",
                        "",
                        "",
                        "task_id",
                        false,
                        java.util.List.of(
                                new VideoModelDef("viduq1", "Vidu Q1", false, false, false, 16, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("viduq2", "Vidu Q2", false, false, false, 16, "video/mp4", java.util.List.of(), 0),
                                new VideoModelDef("viduq3-pro", "Vidu Q3 Pro", false, false, false, 16, "video/mp4", java.util.List.of(), 0)
                        ));
            case ZHIPU:
                return new VideoGenDef(
                        "VideoZhipu",
                        "DeliveryURL",
                        "",
                        java.util.List.of(new RequestVariant(java.util.List.of("TextOnly"), "", "/v4/videos/generations")),
                        "/v4/async-result/{id}",
                        "",
                        "",
                        "id",
                        false,
                        java.util.List.of(
                                new VideoModelDef("cogvideox-3", "CogVideoX-3", false, false, false, 10, "video/mp4", java.util.List.of("1080p", "4k", "720p"), 0)
                        ));
            default: return null;
        }
    }
}
