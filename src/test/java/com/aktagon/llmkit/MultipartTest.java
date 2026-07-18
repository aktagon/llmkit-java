package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * HANDOFF-036 A2: a quote, backslash, or CR/LF in a caller-controlled field
 * name or filename must not break out of the Content-Disposition part header.
 * The shared hostile vector is asserted identically in Go, Swift, and Python.
 */
class MultipartTest {

    @Test
    void hostileFilenameAndFieldNameAreEscaped() {
        String hostileFilename = "evil\"name\\inject\r\nX-Fake: 1.mp3";
        Multipart.Encoded encoded = Multipart.encode(
                Map.of("model\r\nX-Sneak: a", "whisper-1"),
                "file\"field",
                hostileFilename,
                "audio/mpeg",
                "audio-bytes".getBytes(StandardCharsets.UTF_8));

        String payload = new String(encoded.payload(), StandardCharsets.UTF_8);
        assertTrue(
                payload.contains("filename=\"evil\\\"name\\\\injectX-Fake: 1.mp3\""),
                "filename not escaped: " + payload);
        assertTrue(
                payload.contains("name=\"file\\\"field\""),
                "file field name not escaped: " + payload);
        assertTrue(
                payload.contains("name=\"modelX-Sneak: a\""),
                "text field name not CR/LF-stripped: " + payload);
        assertFalse(payload.contains("\nX-Fake"), "raw CR/LF leaked: " + payload);
        assertFalse(payload.contains("\nX-Sneak"), "raw CR/LF leaked: " + payload);
    }
}
