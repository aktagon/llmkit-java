package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.BatchHandle;
import com.aktagon.llmkit.providers.generated.Response;
import java.util.List;

/**
 * The live half of a submitted batch (ADR-064 batch-as-text-execution-mode).
 * The generated {@link BatchHandle} value carries only identity (id +
 * provider + raw) — the credential-bearing, transport-holding live handle is
 * this handwritten wrapper around it, bound to the shared Job engine
 * (ADR-062). {@link #handle} is the persistable value for cross-process
 * resume (ADR-014).
 */
public final class BatchJob {
    /** The persistable identity value (ADR-014 cross-process resume). */
    public final BatchHandle handle;

    private final String apiKey;
    private final HttpTransport http;
    private final String baseUrlOverride;
    /** Poll cadence for {@link #await} (tests shrink these; defaults match Go/Rust). */
    long pollIntervalMillis = 2_000;
    long pollTimeoutMillis = 600_000;

    BatchJob(BatchHandle handle, String apiKey, HttpTransport http, String baseUrlOverride) {
        this.handle = handle;
        this.apiKey = apiKey;
        this.http = http;
        this.baseUrlOverride = baseUrlOverride;
    }

    /** One normalized poll round-trip (ADR-063 POLL-001): no loop. */
    public JobStatus<List<Response>> poll() {
        return Job.pollOnce(adapter());
    }

    /**
     * Poll until a terminal state, returning the ordered responses. Named
     * {@code await} (not {@code wait}) because {@code Object.wait()} is final
     * in Java — the one per-language rename in the Wait entry point.
     */
    public List<Response> await() {
        Batching.BatchAdapter adapter = adapter();
        adapter.lc.pollIntervalMillis = pollIntervalMillis;
        adapter.lc.pollTimeoutMillis = pollTimeoutMillis;
        return Job.pollJob(adapter);
    }

    private Batching.BatchAdapter adapter() {
        return new Batching.BatchAdapter(handle.provider(), apiKey, http, baseUrlOverride, handle.id());
    }
}
