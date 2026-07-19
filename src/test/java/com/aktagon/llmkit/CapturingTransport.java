package com.aktagon.llmkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/*




*/
final class CapturingTransport implements HttpTransport {
    String capturedUrl;
    String capturedBody;
    Map<String, String> capturedHeaders;
    /**/
    final List<String> urls = new ArrayList<>();

    int responseStatusCode = 200;
    byte[] responseBody = new byte[0];
    /**/
    final Deque<byte[]> responseSequence = new ArrayDeque<>();

    CapturingTransport withResponse(int statusCode, String body) {
        this.responseStatusCode = statusCode;
        this.responseBody = body.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    /**/
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
            String fileContentType,
            byte[] data,
            Map<String, String> headers) {
        //
        //
        //
        Multipart.Encoded encoded = Multipart.encode(fields, fileField, filename, fileContentType, data);
        this.capturedUrl = url;
        this.capturedBody = new String(encoded.payload(), StandardCharsets.UTF_8);
        Map<String, String> withContentType = new java.util.LinkedHashMap<>(headers);
        withContentType.put("content-type", "multipart/form-data; boundary=" + encoded.boundary());
        this.capturedHeaders = withContentType;
        urls.add(url);
        return new Result(responseStatusCode, nextBody());
    }

    @Override
    public Result postBytes(String url, byte[] body, Map<String, String> headers) {
        this.capturedUrl = url;
        this.capturedBody = new String(body, StandardCharsets.UTF_8);
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
