package com.aktagon.llmkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A transport that captures the outbound request and returns canned
 * responses, letting the request-wire driver assert the exact bytes the SDK
 * builds without a live network call (mirrors Swift's MockURLProtocol,
 * including its scripted response sequence for multi-hop flows).
 */
final class CapturingTransport implements HttpTransport {
    String capturedUrl;
    String capturedBody;
    Map<String, String> capturedHeaders;
    /** Every URL touched, in order (poll + result hops). */
    final List<String> urls = new ArrayList<>();

    int responseStatusCode = 200;
    byte[] responseBody = new byte[0];
    /** When non-empty, each exchange consumes the next canned body. */
    final Deque<byte[]> responseSequence = new ArrayDeque<>();

    CapturingTransport withResponse(int statusCode, String body) {
        this.responseStatusCode = statusCode;
        this.responseBody = body.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    /** Canned response as raw bytes (e.g. OpenAI's binary TTS reply, never JSON). */
    CapturingTransport withResponseBytes(int statusCode, byte[] body) {
        this.responseStatusCode = statusCode;
        this.responseBody = body;
        return this;
    }

    CapturingTransport enqueue(String body) {
        responseSequence.add(body.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    private byte[] nextBody() {
        byte[] next = responseSequence.poll();
        return next != null ? next : responseBody;
    }

    @Override
    public Result postJson(String url, String body, Map<String, String> headers) {
        this.capturedUrl = url;
        this.capturedBody = body;
        this.capturedHeaders = headers;
        urls.add(url);
        return new Result(responseStatusCode, nextBody());
    }

    @Override
    public Result getText(String url, Map<String, String> headers) {
        this.capturedUrl = url;
        this.capturedHeaders = headers;
        urls.add(url);
        return new Result(responseStatusCode, nextBody());
    }

    @Override
    public Result postMultipart(
            String url,
            Map<String, String> fields,
            String fileField,
            String filename,
            byte[] data,
            Map<String, String> headers) {
        this.capturedUrl = url;
        this.capturedBody = new String(data, StandardCharsets.UTF_8);
        this.capturedHeaders = headers;
        urls.add(url);
        return new Result(responseStatusCode, nextBody());
    }

    @Override
    public StreamResult postJsonStreaming(String url, String body, Map<String, String> headers) {
        this.capturedUrl = url;
        this.capturedBody = body;
        this.capturedHeaders = headers;
        urls.add(url);
        return new StreamResult(
                responseStatusCode, new String(nextBody(), StandardCharsets.UTF_8).lines());
    }
}
