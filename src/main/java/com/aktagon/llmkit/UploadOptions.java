package com.aktagon.llmkit;

import java.util.ArrayList;
import java.util.List;

/**
 * The accumulated Files-API upload parameters carried by the {@link Upload}
 * builder (mirrors Swift's {@code Upload} struct fields / Rust's {@code
 * upload_bytes} args). Package-private — the public surface is the builder
 * chain. Mutable; {@link Upload} clones it on each chain step so builders
 * stay immutable value-like.
 */
final class UploadOptions {
    String path;
    byte[] bytes;
    String filename;
    String mimeType;
    /** Observation + veto hooks fired around the {@code upload} op. */
    List<MiddlewareFn> middleware = new ArrayList<>();

    UploadOptions() {}

    /** A deep-enough copy for clone-on-chain (lists copied, scalars shared). */
    UploadOptions copy() {
        UploadOptions o = new UploadOptions();
        o.path = path;
        o.bytes = bytes;
        o.filename = filename;
        o.mimeType = mimeType;
        o.middleware = new ArrayList<>(middleware);
        return o;
    }
}
