package com.aktagon.llmkit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** Default transport over {@code java.net.http} (ADR-068 JAVA-003). */
final class JdkHttpTransport implements HttpTransport {
    // Connect timeout mirrors Go's DefaultTransport 30s dial timeout; no
    // per-request timeout (Go has none — long generations may stream slowly).
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    @Override
    public Result postJson(String url, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = requestBuilder(url);
        builder.setHeader("Content-Type", "application/json");
        applyHeaders(builder, headers);
        builder.POST(HttpRequest.BodyPublishers.ofString(body));
        return send(builder);
    }

    @Override
    public Result getText(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = requestBuilder(url);
        applyHeaders(builder, headers);
        builder.GET();
        return send(builder);
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
        Multipart.Encoded encoded = Multipart.encode(fields, fileField, filename, fileContentType, data);
        HttpRequest.Builder builder = requestBuilder(url);
        builder.setHeader("Content-Type", "multipart/form-data; boundary=" + encoded.boundary());
        applyHeaders(builder, headers);
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(encoded.payload()));
        return send(builder);
    }

    @Override
    public Result postBytes(String url, byte[] body, Map<String, String> headers) {
        HttpRequest.Builder builder = requestBuilder(url);
        builder.setHeader("Content-Type", "application/octet-stream");
        applyHeaders(builder, headers);
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return send(builder);
    }

    @Override
    public StreamResult postJsonStreaming(String url, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = requestBuilder(url);
        builder.setHeader("Content-Type", "application/json");
        applyHeaders(builder, headers);
        builder.POST(HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<java.util.stream.Stream<String>> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
            return new StreamResult(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new TransportException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("interrupted", e);
        }
    }

    private static HttpRequest.Builder requestBuilder(String url) {
        try {
            return HttpRequest.newBuilder(URI.create(url));
        } catch (IllegalArgumentException e) {
            // Do not echo `url` — it can carry the API key (spliced into the
            // path via {apiKey} or into a ?key= query param), which would leak
            // the credential into the exception message, logs, and stack traces.
            throw new ValidationException("url", "malformed request URL");
        }
    }

    /**
     * Apply caller headers, skipping the ones {@code java.net.http} restricts
     * (Host, Content-Length — set by the client itself). SigV4's signed Host
     * rides implicitly: the client always sends Host = URI host, which is the
     * value the signature covered.
     */
    private static void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (name.equals("host") || name.equals("content-length")) {
                continue;
            }
            builder.setHeader(header.getKey(), header.getValue());
        }
    }

    private Result send(HttpRequest.Builder builder) {
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
