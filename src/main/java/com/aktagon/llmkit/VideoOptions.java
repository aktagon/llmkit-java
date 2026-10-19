package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.MediaRef;
import java.util.ArrayList;
import java.util.List;

/**
 * The accumulated video-generation parameters carried by the {@link Video}
 * builder (mirrors Swift's {@code Video} stored properties / Rust's
 * {@code VideoOptions}). Package-private — the public surface is the builder
 * chain. Mutable; {@link Video} clones it on each chain step so builders stay
 * immutable value-like.
 */
final class VideoOptions {
    /** Caller S3 destination URI for output-uri delivery (Bedrock Nova Reel). */
    String outputUri = "";
    /** The final frame the video ends on (BUG-089); null when unset. */
    MediaRef lastFrame = null;
    /** Asset reference images, in caller order (BUG-089). */
    List<MediaRef> referenceImages = new ArrayList<>();
    /** Video length in whole seconds; 0 = unset (HANDOFF-097). */
    int durationSeconds = 0;
    /** Output aspect ratio such as "16:9"; "" = unset (HANDOFF-097). */
    String aspectRatio = "";
    /** Output resolution such as "720p"; "" = unset (HANDOFF-097). */
    String resolution = "";
    boolean raw = false;
    /** Observation + veto hooks fired around the {@code videoGeneration} op. */
    List<MiddlewareFn> middleware = new ArrayList<>();

    VideoOptions() {}

    /** A deep-enough copy for clone-on-chain (lists copied, scalars shared). */
    VideoOptions copy() {
        VideoOptions o = new VideoOptions();
        o.outputUri = outputUri;
        o.lastFrame = lastFrame;
        o.referenceImages = new ArrayList<>(referenceImages);
        o.durationSeconds = durationSeconds;
        o.aspectRatio = aspectRatio;
        o.resolution = resolution;
        o.raw = raw;
        o.middleware = new ArrayList<>(middleware);
        return o;
    }
}
