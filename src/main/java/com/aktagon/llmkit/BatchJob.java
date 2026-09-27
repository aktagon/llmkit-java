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
    private final BatchHandle handle;
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

    /** The persistable identity value (ADR-014 cross-process resume). */
    public BatchHandle handle() {
        return handle;
    }

    /** One normalized poll round-trip (ADR-063 POLL-001): no loop. */
    public JobStatus<List<Response>> poll() {
        return Job.pollOnce(adapter());
    }

    /**
     * Poll until a terminal state, returning one Response per prompt, at the
     * prompt's index. A failed request keeps its slot: empty text,
     * finishReason set to the provider's reason ("errored", "expired",
     * "batch_expired", an error code; "error" when the provider has none) and
     * finishMessage set to the provider's error message. A request with no
     * result line reads finishReason "missing". Results whose request id is
     * not one this SDK assigned (a batch created elsewhere, resumed by ID)
     * follow the indexed ones in file order. Named {@code await} (not {@code wait}) because
     * {@code Object.wait()} is final in Java — the one per-language rename in
     * the Wait entry point.
     */
    public List<Response> await() {
        Batching.BatchAdapter adapter = adapter();
        adapter.lc.pollIntervalMillis = pollIntervalMillis;
        adapter.lc.pollTimeoutMillis = pollTimeoutMillis;
        return Job.pollJob(adapter);
    }

    private Batching.BatchAdapter adapter() {
        return new Batching.BatchAdapter(
                handle.provider(), apiKey, http, baseUrlOverride, handle.id(), handle.raw());
    }
}
