package com.aktagon.llmkit;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A transport that captures the outbound request and returns a canned
 * response, letting the request-wire driver assert the exact bytes the SDK
 * builds without a live network call (mirrors Swift's MockURLProtocol).
 */
final class CapturingTransport implements HttpTransport {
    String capturedUrl;
    String capturedBody;
    Map<String, String> capturedHeaders;

    int responseStatusCode = 200;
    byte[] responseBody = new byte[0];

    CapturingTransport withResponse(int statusCode, String body) {
        this.responseStatusCode = statusCode;
        this.responseBody = body.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    @Override
    public Result postJson(String url, String body, Map<String, String> headers) {
        this.capturedUrl = url;
        this.capturedBody = body;
        this.capturedHeaders = headers;
        return new Result(responseStatusCode, responseBody);
    }
}
