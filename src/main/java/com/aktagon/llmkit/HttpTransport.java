package com.aktagon.llmkit;

import java.util.Map;

/**
 * Thin transport seam. Injected into {@link Client} so tests can supply a
 * capturing fake — the key testability hook for the request-wire driver
 * (mirrors Swift's injected URLSession / Go's http.Client field).
 */
interface HttpTransport {
    /** Status code + raw response bytes of one HTTP exchange. */
    record Result(int statusCode, byte[] body) {}

    /** Status code + the incremental line stream of an SSE exchange. */
    record StreamResult(int statusCode, java.util.stream.Stream<String> lines) {}

    /**
     * POST a JSON body. The body string is exactly what goes on the wire —
     * serialization happens in the request builder, so the outbound bytes
     * are what the wire goldens assert.
     */
    Result postJson(String url, String body, Map<String, String> headers);

    /** GET a URL — the batch poll + result-fetch hops. */
    Result getText(String url, Map<String, String> headers);

    /**
     * POST a {@code multipart/form-data} body with text fields + one file
     * part — the OpenAI batch file-reference upload hop and the OpenAI
     * synchronous transcription request (ADR-051). The file part carries its
     * own {@code fileContentType} so the transcription wire golden asserts
     * the real audio mime (audio/mpeg), not a blanket octet-stream. Fields
     * are emitted in the caller's iteration order so the encoded body decodes
     * to the same canonical descriptor across all six SDKs (ADR-051 OQ-3).
     */
    Result postMultipart(
            String url,
            Map<String, String> fields,
            String fileField,
            String filename,
            String fileContentType,
            byte[] data,
            Map<String, String> headers);

    /**
     * POST raw bytes with an {@code application/octet-stream} body — the
     * AssemblyAI transcription upload hop (STT-005): local audio bytes are
     * uploaded first to obtain a URL the JSON submit body can reference.
     */
    Result postBytes(String url, byte[] body, Map<String, String> headers);

    /**
     * Open a streaming (SSE) POST. Returns the status code and the line
     * stream; the caller parses {@code event:} / {@code data:} frames.
     */
    StreamResult postJsonStreaming(String url, String body, Map<String, String> headers);
}
