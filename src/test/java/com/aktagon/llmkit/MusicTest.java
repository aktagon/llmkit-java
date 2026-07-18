package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.MusicResponse;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Mock-server unit tests for the music-generation capability ({@link Music}).
 * There is no cross-SDK wire golden for music, so parity is held by these
 * unit tests (mirroring Rust's {@code music.rs} / Swift's MusicTests). Each
 * drives the real {@code client.music().generate(...)} path against a canned
 * provider reply and asserts {@code actual == expected} on the request body
 * and the decoded {@link MusicResponse}, exercising all three wire shapes
 * ({@code MusicMinimax} / {@code MusicPredict} / {@code MusicGenerateContent})
 * selected by the generated {@code musicGenConfig(provider).wireShape} —
 * never provider name.
 */
class MusicTest {
    /** A short fake MP3 the MiniMax hex path round-trips. */
    private static final byte[] FAKE_MP3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00, 0x6D, 0x70, 0x33};
    /** A real 44-byte WAV header (base64) for the Vertex/Gemini base64 paths. */
    private static final String WAV_BASE64 = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YQAAAAA=";

    private CapturingTransport transport;

    private Client client(ProviderName provider, String response) {
        transport = new CapturingTransport().withResponse(200, response);
        return new Client(provider, "key", transport);
    }

    private static String hexEncode(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    // --- MiniMax (MusicMinimax shape: hex audio) ---

    @Test
    void minimaxBodyPromptOnlyOmitsLyrics() {
        client(ProviderName.MINIMAX, "{\"data\":{\"audio\":\"\"}}").music()
                .model("music-2.6").generate("lofi hip hop");

        JsonElement body = Json.parse(transport.capturedBody);
        assertEquals("music-2.6", Json.stringAt(body, "model"));
        assertEquals("lofi hip hop", Json.stringAt(body, "prompt"));
        assertEquals("hex", Json.stringAt(body, "output_format"));
        assertEquals(44100L, Json.longAt(body, "audio_setting.sample_rate"));
        assertEquals(128000L, Json.longAt(body, "audio_setting.bitrate"));
        assertEquals("mp3", Json.stringAt(body, "audio_setting.format"));
        assertEquals("", Json.stringAt(body, "lyrics"));
    }

    @Test
    void minimaxBodyLyricsPartBuildsLyricsField() {
        client(ProviderName.MINIMAX, "{\"data\":{\"audio\":\"\"}}").music()
                .model("music-2.6").text("pop ballad").lyrics("[chorus] hold on").generate("");

        JsonElement body = Json.parse(transport.capturedBody);
        assertEquals("pop ballad", Json.stringAt(body, "prompt"));
        assertEquals("[chorus] hold on", Json.stringAt(body, "lyrics"));
    }

    @Test
    void minimaxResponseHexRoundTripsWithConfigMime() {
        String response = "{\"data\":{\"audio\":\"" + hexEncode(FAKE_MP3)
                + "\"},\"base_resp\":{\"status_code\":0,\"status_msg\":\"success\"}}";

        MusicResponse resp = client(ProviderName.MINIMAX, response).music()
                .model("music-2.6").generate("lofi hip hop");

        assertEquals(1, resp.audio().size());
        assertArrayEquals(FAKE_MP3, resp.audio().get(0).bytes());
        assertEquals("audio/mpeg", resp.audio().get(0).mimeType());
        // status_msg "success" is not surfaced as a finish message.
        assertEquals("", resp.finishMessage());
    }

    @Test
    void minimaxResponseSurfacesNonSuccessStatusMsg() {
        String response = "{\"data\":{\"audio\":\"\"},\"base_resp\":{\"status_code\":1004,\"status_msg\":\"invalid api key\"}}";

        MusicResponse resp = client(ProviderName.MINIMAX, response).music()
                .model("music-2.6").generate("lofi hip hop");

        assertEquals(0, resp.audio().size());
        assertEquals("invalid api key", resp.finishMessage());
    }

    // --- Vertex (MusicPredict shape: instances/parameters, base64 audio) ---

    @Test
    void vertexBodyAndResponse() {
        String response = "{\"predictions\":[{\"audioContent\":\"" + WAV_BASE64 + "\",\"mimeType\":\"audio/wav\"}]}";

        MusicResponse resp = client(ProviderName.VERTEX, response).music()
                .model("lyria-002").generate("ambient soundscape");

        JsonElement body = Json.parse(transport.capturedBody);
        assertEquals("ambient soundscape", Json.stringAt(body, "instances[0].prompt"));
        assertEquals(1L, Json.longAt(body, "parameters.sampleCount"));
        assertEquals(1, resp.audio().size());
        assertEquals("audio/wav", resp.audio().get(0).mimeType());
        assertEquals(44, resp.audio().get(0).bytes().length);
    }

    @Test
    void vertexFoldsLyricsIntoPrompt() {
        client(ProviderName.VERTEX, "{\"predictions\":[]}").music()
                .model("lyria-002").lyrics("hum along").generate("gentle piece");

        // Lyria 2 has no lyrics wire-slot; lyrics fold into the prompt text.
        JsonElement body = Json.parse(transport.capturedBody);
        assertEquals("gentle piece\nhum along", Json.stringAt(body, "instances[0].prompt"));
    }

    // --- Gemini (MusicGenerateContent shape: contents/parts, base64 audio) ---

    @Test
    void geminiBodyAndResponse() {
        String response = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":["
                + "{\"text\":\"la la la\"},"
                + "{\"inlineData\":{\"mimeType\":\"audio/mpeg\",\"data\":\"" + WAV_BASE64 + "\"}}]}}]}";

        MusicResponse resp = client(ProviderName.GOOGLE, response).music()
                .model("lyria-3-pro-preview").generate("an upbeat melody");

        JsonElement body = Json.parse(transport.capturedBody);
        assertEquals("an upbeat melody", Json.stringAt(body, "contents[0].parts[0].text"));
        assertEquals("AUDIO", Json.stringAt(body, "generationConfig.responseModalities[0]"));
        assertEquals(1, resp.audio().size());
        assertEquals("audio/mpeg", resp.audio().get(0).mimeType());
        assertEquals("la la la", resp.text());
        assertEquals("STOP", resp.finishReason());
    }

    // --- Raw opt-in + middleware ---

    @Test
    void rawOptInPopulatesRaw() {
        String response = "{\"data\":{\"audio\":\"" + hexEncode(FAKE_MP3)
                + "\"},\"base_resp\":{\"status_code\":0,\"status_msg\":\"success\"}}";

        MusicResponse resp = client(ProviderName.MINIMAX, response).music()
                .model("music-2.6").raw().generate("lofi hip hop");

        assertNotNull(resp.raw());
        assertEquals(0L, Json.longAt(resp.raw(), "base_resp.status_code"));
    }

    @Test
    void middlewareFiresPreAndPost() {
        String response = "{\"data\":{\"audio\":\"" + hexEncode(FAKE_MP3)
                + "\"},\"base_resp\":{\"status_code\":0,\"status_msg\":\"success\"}}";
        List<MiddlewarePhase> phases = new ArrayList<>();
        MiddlewareFn hook = event -> {
            phases.add(event.phase());
            return null;
        };

        client(ProviderName.MINIMAX, response).music()
                .model("music-2.6").addMiddleware(hook).generate("lofi hip hop");

        assertTrue(phases.contains(MiddlewarePhase.PRE));
        assertTrue(phases.contains(MiddlewarePhase.POST));
    }

    @Test
    void middlewarePreVetoAborts() {
        RuntimeException denied = new RuntimeException("policy");
        MiddlewareVetoException thrown = assertThrows(
                MiddlewareVetoException.class,
                () -> client(ProviderName.MINIMAX, "{}").music()
                        .model("music-2.6").addMiddleware(event -> denied).generate("lofi hip hop"));

        assertEquals(denied, thrown.getCause());
    }

    // --- Validation ---

    @Test
    void requiresModel() {
        ValidationException thrown = assertThrows(
                ValidationException.class, () -> client(ProviderName.MINIMAX, "{}").music().generate("a song"));
        assertEquals("model", thrown.field());
    }

    @Test
    void rejectsBothEmpty() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.MINIMAX, "{}").music().model("music-2.6").generate(""));
        assertEquals("prompt", thrown.field());
    }

    @Test
    void rejectsUnknownProvider() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.OPENAI, "{}").music().model("whatever").generate("x"));
        assertEquals("provider", thrown.field());
    }

    @Test
    void rejectsUnknownModel() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.MINIMAX, "{}").music().model("music-9.9").generate("x"));
        assertEquals("model", thrown.field());
    }
}
