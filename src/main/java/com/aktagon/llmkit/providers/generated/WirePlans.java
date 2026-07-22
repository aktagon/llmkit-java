// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

import java.util.List;
import java.util.Map;

/**
 * Body plans (ADR-073 T1 config-data interpreter — VIDEO PROVING CUT).
 *
 * Emitted as DATA: each plan is an ordered list of field bindings a
 * handwritten interpreter (slice 3) walks to build a video submit body. No
 * runtime reads these tables yet. The vocabulary is CLOSED (INT-001): five
 * sources (Model | Prompt | Option | Const | MediaRef), two transforms
 * (None | DataUri).
 */
public final class WirePlans {
    private WirePlans() {}

    public static final BodyPlan PLAN_VIDEO_BEDROCK = new BodyPlan(
            "video-bedrock",
            List.of(
                    new FieldBinding("modelId", "Model", "", "", "", "None", false),
                    new FieldBinding("modelInput.taskType", "Const", "", "\"TEXT_VIDEO\"", "", "None", false),
                    new FieldBinding("modelInput.textToVideoParams.text", "Prompt", "", "", "", "None", false),
                    new FieldBinding("outputDataConfig.s3OutputDataConfig.s3Uri", "Option", "output_uri", "", "", "None", false)
            ));
    public static final BodyPlan PLAN_VIDEO_GROK = new BodyPlan(
            "video-grok",
            List.of(
                    new FieldBinding("model", "Model", "", "", "", "None", false),
                    new FieldBinding("prompt", "Prompt", "", "", "", "None", false),
                    new FieldBinding("image.url", "MediaRef", "", "", "", "DataUri", true)
            ));
    public static final BodyPlan PLAN_VIDEO_MODEL_PROMPT = new BodyPlan(
            "video-model-prompt",
            List.of(
                    new FieldBinding("model", "Model", "", "", "", "None", false),
                    new FieldBinding("prompt", "Prompt", "", "", "", "None", false)
            ));
    public static final BodyPlan PLAN_VIDEO_PIX_VERSE = new BodyPlan(
            "video-pixverse",
            List.of(
                    new FieldBinding("model", "Model", "", "", "", "None", false),
                    new FieldBinding("prompt", "Prompt", "", "", "", "None", false),
                    new FieldBinding("duration", "Option", "duration", "", "5", "None", false),
                    new FieldBinding("quality", "Option", "quality", "", "\"540p\"", "None", false),
                    new FieldBinding("aspect_ratio", "Option", "aspect_ratio", "", "\"16:9\"", "None", false)
            ));
    public static final BodyPlan PLAN_VIDEO_QWEN = new BodyPlan(
            "video-qwen",
            List.of(
                    new FieldBinding("input.prompt", "Prompt", "", "", "", "None", false),
                    new FieldBinding("model", "Model", "", "", "", "None", false)
            ));
    public static final BodyPlan PLAN_VIDEO_VEO_INSTANCES = new BodyPlan(
            "video-veo-instances",
            List.of(
                    new FieldBinding("instances[0].prompt", "Prompt", "", "", "", "None", false)
            ));

    /** Maps a video wire shape to its body plan (ADR-073). */
    public static final Map<String, BodyPlan> VIDEO_BODY_PLANS = Map.ofEntries(
            Map.entry("VideoBedrock", PLAN_VIDEO_BEDROCK),
            Map.entry("VideoGrok", PLAN_VIDEO_GROK),
            Map.entry("VideoMinimax", PLAN_VIDEO_MODEL_PROMPT),
            Map.entry("VideoPixVerse", PLAN_VIDEO_PIX_VERSE),
            Map.entry("VideoQwen", PLAN_VIDEO_QWEN),
            Map.entry("VideoTogether", PLAN_VIDEO_MODEL_PROMPT),
            Map.entry("VideoVeo", PLAN_VIDEO_VEO_INSTANCES),
            Map.entry("VideoVertexVeo", PLAN_VIDEO_VEO_INSTANCES),
            Map.entry("VideoVidu", PLAN_VIDEO_MODEL_PROMPT),
            Map.entry("VideoZhipu", PLAN_VIDEO_MODEL_PROMPT)
    );
}
