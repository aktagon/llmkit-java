package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.VideoHandle;
import com.aktagon.llmkit.providers.generated.VideoResponse;

/**
 * The live half of a submitted video-generation job (ADR-034, ADR-062). The
 * generated {@link VideoHandle} value carries only identity (id + provider +
 * raw + model) — the credential-bearing, transport-holding live handle is
 * this handwritten wrapper around it, bound to the shared Job engine
 * (ADR-062). {@link #handle} is the persistable value for cross-process
 * resume (ADR-014).
 */
public final class VideoJob {
    private final VideoHandle handle;
    private final String apiKey;
    private final HttpTransport http;
    private final String baseUrlOverride;
    /** Poll cadence for {@link #await} (tests shrink these; defaults match Go/Rust/Swift). */
    long pollIntervalMillis = 5_000;
    long pollTimeoutMillis = 600_000;

    VideoJob(VideoHandle handle, String apiKey, HttpTransport http, String baseUrlOverride) {
        this.handle = handle;
        this.apiKey = apiKey;
        this.http = http;
        this.baseUrlOverride = baseUrlOverride;
    }

    /** The persistable identity value (ADR-014 cross-process resume). */
    public VideoHandle handle() {
        return handle;
    }

    /** One normalized poll round-trip (ADR-063 POLL-001): no loop. */
    public JobStatus<VideoResponse> poll() {
        return Job.pollOnce(adapter());
    }

    /**
     * Poll until a terminal state, returning the finished {@code
     * VideoResponse}. Named {@code await} (not {@code wait}) because {@code
     * Object.wait()} is final in Java — the one per-language rename in the
     * Wait entry point.
     */
    public VideoResponse await() {
        VideoPoll.VideoAdapter adapter = adapter();
        adapter.lc.pollIntervalMillis = pollIntervalMillis;
        adapter.lc.pollTimeoutMillis = pollTimeoutMillis;
        return Job.pollJob(adapter);
    }

    private VideoPoll.VideoAdapter adapter() {
        return new VideoPoll.VideoAdapter(
                handle.provider(), apiKey, http, baseUrlOverride, handle.id(), handle.model(), handle.raw());
    }
}
