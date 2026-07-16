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
     * part — the OpenAI batch file-reference upload hop.
     */
    Result postMultipart(
            String url,
            Map<String, String> fields,
            String fileField,
            String filename,
            byte[] data,
            Map<String, String> headers);

    /**
     * Open a streaming (SSE) POST. Returns the status code and the line
     * stream; the caller parses {@code event:} / {@code data:} frames.
     */
    StreamResult postJsonStreaming(String url, String body, Map<String, String> headers);
}
