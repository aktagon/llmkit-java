package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.VideoHandle;
import com.aktagon.llmkit.providers.generated.VideoResponse;

/*






*/
public final class VideoJob {
    private final VideoHandle handle;
    private final String apiKey;
    private final HttpTransport http;
    private final String baseUrlOverride;
    /**/
    long pollIntervalMillis = 5_000;
    long pollTimeoutMillis = 600_000;

    VideoJob(VideoHandle handle, String apiKey, HttpTransport http, String baseUrlOverride) {
        this.handle = handle;
        this.apiKey = apiKey;
        this.http = http;
        this.baseUrlOverride = baseUrlOverride;
    }

    /**/
    public VideoHandle handle() {
        return handle;
    }

    /**/
    public JobStatus<VideoResponse> poll() {
        return Job.pollOnce(adapter());
    }

    /*




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
