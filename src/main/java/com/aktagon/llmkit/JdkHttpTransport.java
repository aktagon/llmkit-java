package com.aktagon.llmkit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Default transport over {@code java.net.http} (ADR-068 JAVA-003). */
final class JdkHttpTransport implements HttpTransport {
    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public Result postJson(String url, String body, Map<String, String> headers) {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("url", "invalid URL: " + url);
        }
        builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);
        builder.POST(HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<byte[]> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Result(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new TransportException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("interrupted", e);
        }
    }
}
