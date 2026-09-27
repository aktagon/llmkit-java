package com.aktagon.llmkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared {@code multipart/form-data} encoder (ADR-051 OQ-3): builds the exact
 * wire bytes for a body with ordered text fields plus one or more file parts. Used by
 * both {@link JdkHttpTransport} (the real transport) and {@link
 * CapturingTransport} (the request-wire test double), so the
 * transcription-openai wire fixture decodes the SAME encoding production code
 * sends -- never a test-only approximation.
 */
final class Multipart {
    private Multipart() {}

    record Encoded(String boundary, byte[] payload) {}

    /**
     * Mirrors Go stdlib mime/multipart escapeQuotes and additionally strips
     * CR/LF: a quote or newline in a caller-controlled field name or filename
     * must not break out of the Content-Disposition part header
     * (HANDOFF-036 A2).
     */
    private static String escapeQuotes(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    /** One file part of a multipart body (e.g. OpenAI's {@code image[]} / {@code mask}). */
    record FilePart(String fieldName, String filename, String contentType, byte[] data) {}

    static Encoded encode(
            Map<String, String> fields,
            String fileField,
            String filename,
            String fileContentType,
            byte[] data) {
        return encode(fields, List.of(new FilePart(fileField, filename, fileContentType, data)));
    }

    /** Text fields first (caller iteration order), then the file parts in order. */
    static Encoded encode(Map<String, String> fields, List<FilePart> files) {
        String boundary = "llmkit-boundary-" + UUID.randomUUID();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                payload.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + escapeQuotes(field.getKey()) + "\"\r\n\r\n"
                        + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            for (FilePart file : files) {
                payload.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + escapeQuotes(file.fieldName()) + "\"; filename=\"" + escapeQuotes(file.filename()) + "\"\r\n"
                        + "Content-Type: " + file.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                payload.write(file.data());
                payload.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            payload.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TransportException(e.getMessage(), e);
        }
        return new Encoded(boundary, payload.toByteArray());
    }
}
