package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.TranscriptionHandle;
import com.aktagon.llmkit.providers.generated.TranscriptionResponse;

/**
 * The live half of a submitted transcription job (ADR-048, ADR-062). The
 * generated {@link TranscriptionHandle} value carries only identity (id +
 * provider) — the credential-bearing, transport-holding live handle is this
 * handwritten wrapper around it, bound to the shared Job engine (ADR-062).
 * {@link #handle} is the persistable value for cross-process resume
 * (ADR-014).
 */
public final class TranscriptionJob {
    private final TranscriptionHandle handle;
    private final String apiKey;
    private final HttpTransport http;
    private final String baseUrlOverride;
    /** Poll cadence for {@link #await} (tests shrink these; defaults match Go/Rust/Swift). */
    long pollIntervalMillis = 3_000;

    long pollTimeoutMillis = 600_000;

    TranscriptionJob(TranscriptionHandle handle, String apiKey, HttpTransport http, String baseUrlOverride) {
        this.handle = handle;
        this.apiKey = apiKey;
        this.http = http;
        this.baseUrlOverride = baseUrlOverride;
    }

    /** The persistable identity value (ADR-014 cross-process resume). */
    public TranscriptionHandle handle() {
        return handle;
    }

    /** One normalized poll round-trip (ADR-063 POLL-001): no loop. */
    public JobStatus<TranscriptionResponse> poll() {
        return Job.pollOnce(adapter());
    }

    /**
     * Poll until a terminal state, returning the finished {@code
     * TranscriptionResponse}. Named {@code await} (not {@code wait}) because
     * {@code Object.wait()} is final in Java — the one per-language rename
     * in the Wait entry point.
     */
    public TranscriptionResponse await() {
        Transcription.TranscriptionAdapter adapter = adapter();
        adapter.lc.pollIntervalMillis = pollIntervalMillis;
        adapter.lc.pollTimeoutMillis = pollTimeoutMillis;
        return Job.pollJob(adapter);
    }

    private Transcription.TranscriptionAdapter adapter() {
        return new Transcription.TranscriptionAdapter(handle.provider(), apiKey, http, baseUrlOverride, handle.id());
    }
}
