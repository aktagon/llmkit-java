package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.TranscriptionHandle;
import com.aktagon.llmkit.providers.generated.TranscriptionResponse;

/*






*/
public final class TranscriptionJob {
    private final TranscriptionHandle handle;
    private final String apiKey;
    private final HttpTransport http;
    private final String baseUrlOverride;
    /**/
    long pollIntervalMillis = 3_000;

    long pollTimeoutMillis = 600_000;

    TranscriptionJob(TranscriptionHandle handle, String apiKey, HttpTransport http, String baseUrlOverride) {
        this.handle = handle;
        this.apiKey = apiKey;
        this.http = http;
        this.baseUrlOverride = baseUrlOverride;
    }

    /**/
    public TranscriptionHandle handle() {
        return handle;
    }

    /**/
    public JobStatus<TranscriptionResponse> poll() {
        return Job.pollOnce(adapter());
    }

    /*




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
