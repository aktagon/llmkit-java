package com.aktagon.llmkit;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Default transport over {@code java.net.http} (ADR-068 JAVA-003). */
final class JdkHttpTransport implements HttpTransport {
    // Connect timeout mirrors Go's DefaultTransport 30s dial timeout. The
    // client timeout (BUG-062) is per request: HttpRequest.timeout bounds the
    // wait for headers, IdleTimeoutInputStream bounds each body read.
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    // One daemon thread arms the per-read deadlines for every transport.
    private static final ScheduledThreadPoolExecutor TIMER = newTimer();

    private final Duration timeout;

    JdkHttpTransport(Duration timeout) {
        this.timeout = timeout;
    }

    /** The wait limit for the next bytes; {@link Duration#ZERO} means none. */
    Duration timeout() {
        return timeout;
    }

    @Override
    public HttpTransport withTimeout(Duration timeout) {
        return new JdkHttpTransport(timeout);
    }

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
            String url, Map<String, String> fields, java.util.List<Multipart.FilePart> files, Map<String, String> headers) {
        Multipart.Encoded encoded = Multipart.encode(fields, files);
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
        HttpResponse<InputStream> response = open(builder);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(guard(response.body()), StandardCharsets.UTF_8));
        Stream<String> lines = reader.lines().onClose(() -> {
            try {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return new StreamResult(response.statusCode(), lines);
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
        HttpResponse<InputStream> response = open(builder);
        try (InputStream body = guard(response.body())) {
            return new Result(response.statusCode(), body.readAllBytes());
        } catch (IOException e) {
            throw new TransportException(e.getMessage(), e);
        }
    }

    /** Send and wait for the response headers, bounded by the timeout. */
    private HttpResponse<InputStream> open(HttpRequest.Builder builder) {
        if (!timeout.isZero()) {
            builder.timeout(timeout);
        }
        try {
            return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new TransportException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("interrupted", e);
        }
    }

    private InputStream guard(InputStream body) {
        return timeout.isZero() ? body : new IdleTimeoutInputStream(body, timeout);
    }

    private static ScheduledThreadPoolExecutor newTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "llmkit-http-timeout");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /**
     * Bounds every body read by the idle timeout. Before each read a timer is
     * armed that closes the underlying stream; closing unblocks a pending
     * JDK body read. The read then throws {@link HttpTimeoutException}
     * instead of reporting the early end of stream as a clean EOF.
     */
    private static final class IdleTimeoutInputStream extends FilterInputStream {
        private final Duration timeout;
        private volatile boolean expired;

        IdleTimeoutInputStream(InputStream in, Duration timeout) {
            super(in);
            this.timeout = timeout;
        }

        @Override
        public int read() throws IOException {
            ScheduledFuture<?> deadline = arm();
            try {
                return check(in.read());
            } catch (IOException e) {
                throw expired ? timedOut() : e;
            } finally {
                deadline.cancel(false);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            ScheduledFuture<?> deadline = arm();
            try {
                return check(in.read(b, off, len));
            } catch (IOException e) {
                throw expired ? timedOut() : e;
            } finally {
                deadline.cancel(false);
            }
        }

        private ScheduledFuture<?> arm() throws IOException {
            if (expired) {
                throw timedOut();
            }
            return TIMER.schedule(() -> {
                expired = true;
                try {
                    in.close();
                } catch (IOException ignored) {
                    // The reader sees `expired` and reports the timeout.
                }
            }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private int check(int result) throws IOException {
            if (expired) {
                throw timedOut();
            }
            return result;
        }

        private HttpTimeoutException timedOut() {
            return new HttpTimeoutException("no response bytes for " + timeout.toMillis() + " ms");
        }
    }
}
