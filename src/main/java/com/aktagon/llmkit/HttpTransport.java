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

    /**
     * POST a JSON body. The body string is exactly what goes on the wire —
     * serialization happens in the request builder, so the outbound bytes
     * are what the wire goldens assert.
     */
    Result postJson(String url, String body, Map<String, String> headers);
}
