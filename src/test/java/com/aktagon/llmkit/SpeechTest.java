package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.SpeechResponse;
import com.google.gson.JsonElement;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Mock-server unit tests for the text-to-speech capability ({@link Speech}):
 * response parsing for both audio encodings ({@code base64Envelope} /
 * {@code rawBody}), request bodies per wire shape, and pre-flight validation
 * — not already covered by the wire drivers ({@link RequestWireTest} /
 * {@link ResponseWireTest}). Real domain values, {@code actual == expected}
 * (mirrors Swift's SpeechTests).
 */
class SpeechTest {
    /** A real 44-byte WAV header (base64), same clip the response-wire golden anchors. */
    private static final String WAV_BASE64 = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YQAAAAA=";

    private CapturingTransport transport;

    private Client client(ProviderName provider, String response) {
        transport = new CapturingTransport().withResponse(200, response);
        return new Client(provider, "key", transport);
    }

    private Client clientBytes(ProviderName provider, byte[] response) {
        transport = new CapturingTransport().withResponseBytes(200, response);
        return new Client(provider, "key", transport);
    }

    // --- Response parsing (base64Envelope shape — Inworld) ---

    @Test
    void inworldBase64EnvelopeDecodes() {
        String envelope = "{\"audioContent\":\"" + WAV_BASE64 + "\",\"usage\":{\"processedCharactersCount\":18}}";

        SpeechResponse resp = client(ProviderName.INWORLD, envelope).speech()
                .model("inworld-tts-2").voice("Dennis")
                .generate("Hello from llmkit.");

        assertEquals("audio/wav", resp.audio().mimeType());
        assertArrayEquals(Base64.getDecoder().decode(WAV_BASE64), resp.audio().bytes());
        assertEquals(44, resp.audio().bytes().length);
        // ADR-049 OQ-3: processedCharactersCount is not surfaced (no characters axis).
        assertEquals(Usage.zero(), resp.usage());
        assertEquals("", resp.finishReason());
    }

    @Test
    void inworldEmptyAudioContentYieldsNoBytes() {
        SpeechResponse resp = client(ProviderName.INWORLD, "{\"audioContent\":\"\"}").speech()
                .model("inworld-tts-2").voice("Alex")
                .generate("silence");

        assertEquals("audio/wav", resp.audio().mimeType());
        assertTrue(resp.audio().bytes().length == 0);
    }

    // --- Response parsing (rawBody shape — OpenAI) ---

    @Test
    void openAIRawBodyTakesResponseVerbatim() {
        // OpenAI /v1/audio/speech returns binary audio, not JSON — the reply
        // is the audio bytes verbatim.
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00, 0x6D, 0x70, 0x33};

        SpeechResponse resp = clientBytes(ProviderName.OPENAI, mp3).speech()
                .model("gpt-4o-mini-tts").voice("alloy")
                .generate("Hello from llmkit.");

        assertEquals("audio/mpeg", resp.audio().mimeType());
        assertArrayEquals(mp3, resp.audio().bytes());
        assertEquals(Usage.zero(), resp.usage());
    }

    // --- Request bodies (per wire shape) ---

    @Test
    void inworldRequestBody() {
        client(ProviderName.INWORLD, "{\"audioContent\":\"\"}").speech()
                .model("inworld-tts-2").voice("Dennis")
                .generate("Hello from llmkit.");

        JsonElement body = Json.parse(transport.capturedBody);
        assertEquals("Hello from llmkit.", Json.stringAt(body, "text"));
        assertEquals("Dennis", Json.stringAt(body, "voiceId"));
        assertEquals("inworld-tts-2", Json.stringAt(body, "modelId"));
        assertEquals("LINEAR16", Json.stringAt(body, "audioConfig.audioEncoding"));
        assertEquals(22050L, Json.longAt(body, "audioConfig.sampleRateHertz"));
        assertEquals("BALANCED", Json.stringAt(body, "deliveryMode"));
        // Inworld authenticates with a Basic-prefixed Authorization header.
        assertEquals("Basic key", transport.capturedHeaders.get("Authorization"));
    }

    @Test
    void openAIRequestBody() {
        clientBytes(ProviderName.OPENAI, new byte[] {(byte) 0xFF}).speech()
                .model("gpt-4o-mini-tts").voice("alloy")
                .generate("Hello from llmkit.");

        JsonElement body = Json.parse(transport.capturedBody);
        assertEquals("gpt-4o-mini-tts", Json.stringAt(body, "model"));
        assertEquals("Hello from llmkit.", Json.stringAt(body, "input"));
        assertEquals("alloy", Json.stringAt(body, "voice"));
        assertEquals("mp3", Json.stringAt(body, "response_format"));
    }

    // --- Validation ---

    @Test
    void requiresModel() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.INWORLD, "{}").speech().voice("Dennis").generate("hi"));
        assertEquals("model", thrown.field());
    }

    @Test
    void requiresText() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.INWORLD, "{}").speech()
                        .model("inworld-tts-2").voice("Dennis").generate(""));
        assertEquals("text", thrown.field());
    }

    @Test
    void requiresVoice() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.INWORLD, "{}").speech().model("inworld-tts-2").generate("hi"));
        assertEquals("voice", thrown.field());
    }

    @Test
    void rejectsUnknownVoice() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.INWORLD, "{}").speech()
                        .model("inworld-tts-2").voice("Nonexistent").generate("hi"));
        assertEquals("voice", thrown.field());
    }

    @Test
    void rejectsUnknownModel() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.INWORLD, "{}").speech()
                        .model("inworld-tts-9").voice("Dennis").generate("hi"));
        assertEquals("model", thrown.field());
    }

    @Test
    void rejectsProviderWithoutSpeech() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.ANTHROPIC, "{}").speech()
                        .model("inworld-tts-2").voice("Dennis").generate("hi"));
        assertEquals("provider", thrown.field());
    }
}
