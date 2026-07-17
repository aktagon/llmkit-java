package com.aktagon.llmkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Shared {@code multipart/form-data} encoder (ADR-051 OQ-3): builds the exact
 * wire bytes for a body with ordered text fields plus one file part. Used by
 * both {@link JdkHttpTransport} (the real transport) and {@link
 * CapturingTransport} (the request-wire test double), so the
 * transcription-openai wire fixture decodes the SAME encoding production code
 * sends -- never a test-only approximation.
 */
final class Multipart {
    private Multipart() {}

    record Encoded(String boundary, byte[] payload) {}

    static Encoded encode(
            Map<String, String> fields,
            String fileField,
            String filename,
            String fileContentType,
            byte[] data) {
        String boundary = "llmkit-boundary-" + UUID.randomUUID();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                payload.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                        + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            payload.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + fileContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            payload.write(data);
            payload.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TransportException(e.getMessage(), e);
        }
        return new Encoded(boundary, payload.toByteArray());
    }
}
