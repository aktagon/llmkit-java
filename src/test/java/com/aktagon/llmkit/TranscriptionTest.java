package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.TranscriptSegment;
import com.aktagon.llmkit.providers.generated.TranscriptionResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/*






*/
class TranscriptionTest {
    private CapturingTransport transport;

    private Client client(ProviderName provider) {
        transport = new CapturingTransport();
        return new Client(provider, "key", transport);
    }

    //

    @Test
    void openAITranscribeParsesTextAndSegments() {
        //
        transport = new CapturingTransport().withResponse(200, "{\"task\":\"transcribe\",\"language\":\"english\","
                + "\"duration\":3.5,\"text\":\"Turn left at the harbor.\","
                + "\"segments\":["
                + "{\"id\":0,\"start\":0.0,\"end\":1.5,\"text\":\"Turn left\"},"
                + "{\"id\":1,\"start\":1.5,\"end\":3.5,\"text\":\" at the harbor.\"}"
                + "]}");
        TranscriptionResponse response = new Client(ProviderName.OPENAI, "key", transport).transcription()
                .model("whisper-1")
                .transcribe(List.of(Part.audioBytes("audio/mpeg", "fake-audio".getBytes(StandardCharsets.UTF_8))));

        assertEquals("Turn left at the harbor.", response.text());
        assertEquals(2, response.segments().size());
        assertEquals(new TranscriptSegment("Turn left", 0, 1500, ""), response.segments().get(0));
        assertEquals(new TranscriptSegment(" at the harbor.", 1500, 3500, ""), response.segments().get(1));
        //
        assertEquals(Usage.zero(), response.usage());
    }

    /*



*/
    @Test
    void openAITranscribeMultipartBodyShape() {
        transport = new CapturingTransport().withResponse(200, "{\"text\":\"ok\"}");
        new Client(ProviderName.OPENAI, "key", transport).transcription()
                .model("whisper-1")
                .transcribe(List.of(Part.audioBytes("audio/mpeg", "fake-audio".getBytes(StandardCharsets.UTF_8))));

        String contentType = transport.capturedHeaders.get("content-type");
        assertTrue(contentType.startsWith("multipart/form-data; boundary="));
        JsonElement descriptor = RequestWireTest.multipartToDescriptor(transport.capturedBody, contentType);

        JsonObject expected = Json.object();
        expected.addProperty("_encoding", "multipart/form-data");
        JsonArray fields = new JsonArray();
        JsonObject model = new JsonObject();
        model.addProperty("name", "model");
        model.addProperty("value", "whisper-1");
        fields.add(model);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("name", "response_format");
        responseFormat.addProperty("value", "verbose_json");
        fields.add(responseFormat);
        JsonObject file = new JsonObject();
        file.addProperty("name", "file");
        file.addProperty("filename", "audio.mp3");
        file.addProperty("contentType", "audio/mpeg");
        file.addProperty("bytes", "<audio-bytes>");
        fields.add(file);
        expected.add("fields", fields);

        assertEquals(expected, descriptor);
    }

    /*


*/
    @Test
    void openAITranscribeRejectsRemoteURL() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.OPENAI).transcription()
                        .model("whisper-1")
                        .transcribe(List.of(Part.audio("https://storage.example.com/clip.mp3"))));
        assertEquals("parts", thrown.field());
        assertTrue(thrown.getMessage().contains("inline audio bytes only"), "got: " + thrown.getMessage());
    }

    /**/
    @Test
    void transcribeOnAsyncProviderRejects() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.ASSEMBLYAI).transcription()
                        .model("best")
                        .transcribe(List.of(
                                Part.audioBytes("audio/mpeg", "x".getBytes(StandardCharsets.UTF_8)))));
        assertEquals("interaction", thrown.field());
        assertTrue(thrown.getMessage().contains("use submit"), "got: " + thrown.getMessage());
    }

    //

    @Test
    void assemblyAISubmitWaitReturnsTranscript() {
        transport = new CapturingTransport()
                .enqueue("{\"id\":\"transcript_abc123\"}") // submit
                .enqueue("{\"status\":\"queued\"}") // poll: running
                .enqueue("{\"status\":\"processing\"}") // poll: running
                .enqueue("{\"status\":\"completed\",\"text\":\"Turn left at the harbor.\",\"words\":["
                        + "{\"text\":\"Turn\",\"start\":120,\"end\":360,\"speaker\":\"A\"},"
                        + "{\"text\":\"left\",\"start\":360,\"end\":560,\"speaker\":\"A\"}"
                        + "]}"); // poll: done
        TranscriptionJob job = new Client(ProviderName.ASSEMBLYAI, "key", transport).transcription()
                .submit(List.of(Part.audio("https://storage.example.com/meeting-2026-06-24.mp3")));
        assertEquals("transcript_abc123", job.handle().id());
        assertEquals(ProviderName.ASSEMBLYAI, job.handle().provider());

        //
        JsonElement submitBody = Json.parse(transport.capturedBody);
        assertEquals("https://storage.example.com/meeting-2026-06-24.mp3", Json.stringAt(submitBody, "audio_url"));

        job.pollIntervalMillis = 1;
        TranscriptionResponse response = job.await();
        assertEquals("Turn left at the harbor.", response.text());
        assertEquals(2, response.segments().size());
        assertEquals(new TranscriptSegment("Turn", 120, 360, "A"), response.segments().get(0));
        assertEquals(new TranscriptSegment("left", 360, 560, "A"), response.segments().get(1));
        assertEquals(Usage.zero(), response.usage());
    }

    /*



*/
    @Test
    void assemblyAIPollRunningThenSucceeded() {
        transport = new CapturingTransport()
                .enqueue("{\"id\":\"transcript_p1\"}")
                .enqueue("{\"status\":\"processing\"}")
                .enqueue("{\"status\":\"completed\",\"text\":\"Done.\",\"words\":[]}");
        TranscriptionJob job = new Client(ProviderName.ASSEMBLYAI, "key", transport).transcription()
                .submit(List.of(Part.audio("https://storage.example.com/clip.mp3")));

        JobStatus<TranscriptionResponse> first = job.poll();
        assertEquals(JobState.RUNNING, first.state());
        assertEquals("processing", first.rawStatus());
        assertNull(first.result());

        JobStatus<TranscriptionResponse> second = job.poll();
        assertEquals(JobState.SUCCEEDED, second.state());
        assertEquals("completed", second.rawStatus());
        assertEquals("Done.", second.result().text());
        assertEquals(List.of(), second.result().segments());
    }

    /*


*/
    @Test
    void assemblyAIFailedPollThrows() {
        transport = new CapturingTransport()
                .enqueue("{\"id\":\"transcript_bad\"}")
                .enqueue("{\"status\":\"error\",\"error\":\"audio file could not be decoded\"}");
        TranscriptionJob job = new Client(ProviderName.ASSEMBLYAI, "key", transport).transcription()
                .submit(List.of(Part.audio("https://storage.example.com/broken.mp3")));
        job.pollIntervalMillis = 1;

        JobFailedException thrown = assertThrows(JobFailedException.class, job::await);
        assertTrue(thrown.getMessage().contains("audio file could not be decoded"), "got: " + thrown.getMessage());
    }

    /**/
    @Test
    void submitOnSyncProviderRejects() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.OPENAI).transcription()
                        .submit(List.of(Part.audioBytes("audio/mpeg", "x".getBytes(StandardCharsets.UTF_8)))));
        assertEquals("interaction", thrown.field());
        assertTrue(thrown.getMessage().contains("use transcribe"), "got: " + thrown.getMessage());
    }

    /**/
    @Test
    void rejectsNonAudioPart() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.ASSEMBLYAI).transcription().submit(List.of(Part.text("transcribe this"))));
        assertEquals("parts", thrown.field());
        assertTrue(thrown.getMessage().contains("only audio parts"), "got: " + thrown.getMessage());
    }
}
