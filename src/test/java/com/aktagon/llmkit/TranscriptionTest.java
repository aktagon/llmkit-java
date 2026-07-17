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

/**
 * Behavior tests for the transcription capability (ADR-048 / ADR-051) beyond
 * the wire goldens: the OpenAI SYNCHRONOUS {@code transcribe} decode (text +
 * segment timing + the multipart body shape) and the AssemblyAI ASYNCHRONOUS
 * submit -&gt; poll -&gt; result lifecycle over the shared Job engine, driven
 * by a scripted {@link CapturingTransport#enqueue}. Real domain values,
 * {@code actual == expected} (mirrors Swift's TranscriptionTests).
 */
class TranscriptionTest {
    private CapturingTransport transport;

    private Client client(ProviderName provider) {
        transport = new CapturingTransport();
        return new Client(provider, "key", transport);
    }

    // --- OpenAI synchronous transcribe (multipart POST -> transcript) ---

    @Test
    void openAITranscribeParsesTextAndSegments() {
        // verbose_json: offsets are SECONDS (float) -> integer milliseconds.
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
        // AssemblyAI-style token axis is absent for OpenAI TTS -> usage stays zero.
        assertEquals(Usage.zero(), response.usage());
    }

    /**
     * The captured request is a multipart/form-data body carrying the model,
     * response_format, and the audio file part with its real mime + extension
     * — decoded here via the same descriptor decoder the wire driver asserts.
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

    /**
     * The sync path ingests inline bytes only — a remote audio URL is rejected
     * pre-flight (OAA-005, the inverse of AssemblyAI).
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

    /** {@code transcribe} on an asynchronous provider names the right terminal (OAA-003). */
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

    // --- AssemblyAI asynchronous submit -> poll -> result lifecycle ---

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
        assertEquals("transcript_abc123", job.handle.id());
        assertEquals(ProviderName.ASSEMBLYAI, job.handle.provider());

        // The captured submit body is the {audio_url} JSON body.
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

    /**
     * The {@code poll()} primitive (ADR-063): one round-trip -&gt; a
     * normalized status, running then succeeded, safe on a reconstituted
     * handle (cross-process).
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
        assertEquals(JobState.RUNNING, first.state);
        assertEquals("processing", first.rawStatus);
        assertNull(first.result);

        JobStatus<TranscriptionResponse> second = job.poll();
        assertEquals(JobState.SUCCEEDED, second.state);
        assertEquals("completed", second.rawStatus);
        assertEquals("Done.", second.result.text());
        assertEquals(List.of(), second.result.segments());
    }

    /**
     * A status=error transcript surfaces as an error (never a silent empty
     * success); the provider error message rides through.
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

    /** {@code submit} on a synchronous provider names the right terminal (OAA-003). */
    @Test
    void submitOnSyncProviderRejects() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.OPENAI).transcription()
                        .submit(List.of(Part.audioBytes("audio/mpeg", "x".getBytes(StandardCharsets.UTF_8)))));
        assertEquals("interaction", thrown.field());
        assertTrue(thrown.getMessage().contains("use transcribe"), "got: " + thrown.getMessage());
    }

    /** Exactly one audio part is required (STT-003) — a non-audio part is rejected. */
    @Test
    void rejectsNonAudioPart() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.ASSEMBLYAI).transcription().submit(List.of(Part.text("transcribe this"))));
        assertEquals("parts", thrown.field());
        assertTrue(thrown.getMessage().contains("only audio parts"), "got: " + thrown.getMessage());
    }
}
