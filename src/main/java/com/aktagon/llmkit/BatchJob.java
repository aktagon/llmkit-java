package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.BatchHandle;
import com.aktagon.llmkit.providers.generated.Response;
import java.util.List;

/*






*/
public final class BatchJob {
    private final BatchHandle handle;
    private final String apiKey;
    private final HttpTransport http;
    private final String baseUrlOverride;
    /**/
    long pollIntervalMillis = 2_000;
    long pollTimeoutMillis = 600_000;

    BatchJob(BatchHandle handle, String apiKey, HttpTransport http, String baseUrlOverride) {
        this.handle = handle;
        this.apiKey = apiKey;
        this.http = http;
        this.baseUrlOverride = baseUrlOverride;
    }

    /**/
    public BatchHandle handle() {
        return handle;
    }

    /**/
    public JobStatus<List<Response>> poll() {
        return Job.pollOnce(adapter());
    }

    /*



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
