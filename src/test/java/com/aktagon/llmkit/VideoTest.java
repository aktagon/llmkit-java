package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.VideoResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/*






*/
class VideoTest {
    private CapturingTransport transport;

    private Client client(ProviderName provider) {
        transport = new CapturingTransport();
        return new Client(provider, "key", transport);
    }

    //

    @Test
    void grokTextToVideoAwaitReturnsUrl() {
        transport = new CapturingTransport()
                .enqueue("{\"request_id\":\"vid_alpine\"}") // submit
                .enqueue("{\"status\":\"pending\"}") // poll: running
                .enqueue("{\"status\":\"done\",\"video\":{\"url\":\"https://xai.example/vid_alpine.mp4\","
                        + "\"duration\":6}}"); // poll: done
        VideoJob job = new Client(ProviderName.GROK, "key", transport).video()
                .model("grok-imagine-video")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertEquals("vid_alpine", job.handle().id());

        job.pollIntervalMillis = 1;
        VideoResponse response = job.await();
        assertEquals(1, response.videos().size());
        assertEquals("https://xai.example/vid_alpine.mp4", response.videos().get(0).url());
        assertEquals(0, response.videos().get(0).bytes().length);
        assertEquals(6, response.videos().get(0).durationSeconds());
        assertEquals("video/mp4", response.videos().get(0).mimeType());
    }

    /**/
    @Test
    void grokPollRunningThenSucceeded() {
        transport = new CapturingTransport()
                .enqueue("{\"request_id\":\"vid_1\"}")
                .enqueue("{\"status\":\"processing\"}")
                .enqueue("{\"status\":\"done\",\"video\":{\"url\":\"https://xai.example/vid_1.mp4\","
                        + "\"duration\":4}}");
        VideoJob job = new Client(ProviderName.GROK, "key", transport).video()
                .model("grok-imagine-video")
                .submit("A city skyline at night");

        JobStatus<VideoResponse> first = job.poll();
        assertEquals(JobState.RUNNING, first.state());
        assertEquals("processing", first.rawStatus());
        assertEquals(null, first.result());

        JobStatus<VideoResponse> second = job.poll();
        assertEquals(JobState.SUCCEEDED, second.state());
        assertEquals("done", second.rawStatus());
        assertEquals("https://xai.example/vid_1.mp4", second.result().videos().get(0).url());
    }

    //

    @Test
    void grokImageToVideoSubmitBodyCarriesSeedFrame() {
        transport = new CapturingTransport()
                .enqueue("{\"request_id\":\"vid_i2v\"}")
                .enqueue("{\"status\":\"done\",\"video\":{\"url\":\"https://xai.example/vid_i2v.mp4\","
                        + "\"duration\":8}}");
        //
        byte[] seed = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGM4YWQEAALyAS2saifrAAAAAElFTkSuQmCC");
        VideoJob job = new Client(ProviderName.GROK, "key", transport).video()
                .model("grok-imagine-video")
                .image("image/png", seed)
                .submit("Animate the still: a slow cinematic push-in");

        //
        com.google.gson.JsonElement submitBody = Json.parse(transport.capturedBody);
        assertEquals("grok-imagine-video", Json.stringAt(submitBody, "model"));
        assertTrue(Json.stringAt(submitBody, "image.url").startsWith("data:image/png;base64,"));

        job.pollIntervalMillis = 1;
        VideoResponse response = job.await();
        assertEquals("https://xai.example/vid_i2v.mp4", response.videos().get(0).url());
    }

    /*


*/
    @Test
    void textOnlyModelRejectsImageToVideo() {
        byte[] seed = Base64.getDecoder().decode("iVBORw0KGgo=");
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.ZHIPU).video()
                        .model("cogvideox-3").image("image/png", seed).submit("x"));
        assertEquals("parts", thrown.field());
        assertTrue(thrown.getMessage().contains("text-to-video-only"));
    }

    //

    @Test
    void grokFailedPollThrows() {
        transport = new CapturingTransport()
                .enqueue("{\"request_id\":\"vid_bad\"}")
                .enqueue("{\"status\":\"failed\",\"error\":{\"code\":\"content_policy\","
                        + "\"message\":\"blocked by content policy\"}}");
        VideoJob job = new Client(ProviderName.GROK, "key", transport).video()
                .model("grok-imagine-video").submit("something");
        job.pollIntervalMillis = 1;

        JobFailedException thrown = assertThrows(JobFailedException.class, job::await);
        assertTrue(thrown.getMessage().contains("blocked by content policy"), "got: " + thrown.getMessage());
    }

    //

    @Test
    void miniMaxTwoHopResolvesDownloadUrl() {
        transport = new CapturingTransport()
                .enqueue("{\"task_id\":\"mm_1\"}") // submit
                .enqueue("{\"status\":\"Processing\"}") // poll: running
                .enqueue("{\"status\":\"Success\",\"file_id\":\"file_777\"}") // poll: done (file ref)
                .enqueue("{\"file\":{\"download_url\":\"https://minimax.example/mm_1.mp4\"}}"); // file-retrieve
        VideoJob job = new Client(ProviderName.MINIMAX, "key", transport).video()
                .model("MiniMax-Hailuo-2.3")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        assertEquals("mm_1", job.handle().id());

        job.pollIntervalMillis = 1;
        VideoResponse response = job.await();
        assertEquals("https://minimax.example/mm_1.mp4", response.videos().get(0).url());
        assertEquals(0, response.videos().get(0).bytes().length);
    }

    //

    @Test
    void pixVerseSubmitCarriesTraceAndApiKeyHeaders() {
        transport = new CapturingTransport().withResponse(200, "{\"Resp\":{\"video_id\":318633193768896}}");
        VideoJob job = new Client(ProviderName.PIXVERSE, "key", transport).video()
                .model("v4.5")
                .submit("A drone shot sweeping over snow-capped alpine peaks at sunrise");
        //
        assertEquals("318633193768896", job.handle().id());
        assertEquals("key", transport.capturedHeaders.get("API-KEY"));
        assertFalse(transport.capturedHeaders.get("Ai-trace-id").isEmpty());
    }
}
