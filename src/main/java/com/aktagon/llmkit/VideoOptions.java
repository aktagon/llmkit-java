package com.aktagon.llmkit;

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
    boolean raw = false;
    /** Observation + veto hooks fired around the {@code videoGeneration} op. */
    List<MiddlewareFn> middleware = new ArrayList<>();

    VideoOptions() {}

    /** A deep-enough copy for clone-on-chain (lists copied, scalars shared). */
    VideoOptions copy() {
        VideoOptions o = new VideoOptions();
        o.outputUri = outputUri;
        o.raw = raw;
        o.middleware = new ArrayList<>(middleware);
        return o;
    }
}
