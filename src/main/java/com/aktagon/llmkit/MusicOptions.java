package com.aktagon.llmkit;

import java.util.ArrayList;
import java.util.List;

/**
 * The accumulated music-generation parameters carried by the {@link Music}
 * builder (mirrors Swift's {@code MusicOptions} / Rust's {@code MusicOptions}).
 * Package-private — the public surface is the builder chain. Mutable;
 * {@link Music} clones it on each chain step so builders stay immutable
 * value-like.
 */
final class MusicOptions {
    /** Opt-in: populate {@code MusicResponse.raw} with the parsed provider body (ADR-014). */
    boolean raw = false;
    /** Observation + veto hooks fired around the {@code musicGeneration} op. */
    List<MiddlewareFn> middleware = new ArrayList<>();

    MusicOptions() {}

    /** A deep-enough copy for clone-on-chain (lists copied, scalars shared). */
    MusicOptions copy() {
        MusicOptions o = new MusicOptions();
        o.raw = raw;
        o.middleware = new ArrayList<>(middleware);
        return o;
    }
}
