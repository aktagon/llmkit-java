package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.TelemetryGen;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Opt-in observability (ADR-054 / ADR-059) — an OTEL GenAI-aligned OTLP span
 * built on every provider call and handed to a caller-supplied {@code export}.
 * A port of Go's {@code telemetry.go} / Swift's {@code Telemetry.swift}. The
 * generated OTEL constants (semconv version, attribute keys, the operation-
 * name map) live in {@link TelemetryGen}; this file carries the runtime — the
 * {@link Telemetry} config, the fail-open exporter, and the pure {@link
 * TelemetryRuntime#buildOTLPTraces} builder whose cross-SDK parity is held by
 * the telemetry-wire goldens (TEL-011).
 *
 * <p>Unlike Go/TS/Python (whose nullable-callback field defers the honest-
 * contract check to first use), a null {@code export} is rejected right here
 * in the constructor — Java has a real constructor to fail fast in, so an
 * enabled-but-no-sink {@code Telemetry} never escapes into a client. A record
 * with accessor access ({@code telemetry.export()}) per the HANDOFF-036 B2
 * convention.
 *
 * @param export receives the finished OTLP/HTTP proto3-JSON bytes for one
 *     span, called synchronously on the post phase. Mandatory — see class
 *     doc. Use {@link #httpExport} for the batteries POST, or supply your own
 *     to bridge into an existing OTEL stack.
 * @param captureContent gates tier-2 message payloads (default false for
 *     privacy). Reserved — content-log emission is a deferred follow-up
 *     (ADR-054 tier 2).
 */
public record Telemetry(Consumer<byte[]> export, boolean captureContent) {
    public Telemetry(Consumer<byte[]> export) {
        this(export, false);
    }

    public Telemetry {
        if (export == null) {
            throw new ValidationException(
                    "telemetry.export",
                    "export is required when telemetry is enabled (use Telemetry.httpExport for a batteries POST)");
        }
    }

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * A batteries export that POSTs each OTLP payload to {@code endpoint +
     * "/v1/traces"} with a bounded 5s timeout, fail-open (every transport
     * error is swallowed) — mirrors Go's {@code HTTPExport}. Synchronous on
     * the request path like the Go/Rust twins (not fire-and-forget like
     * Swift's URLSession dispatch); a slow collector adds up to the timeout
     * of latency. For high volume, supply your own {@code export} that
     * enqueues into your OTEL SDK's batch processor instead.
     */
    public static Consumer<byte[]> httpExport(String endpoint, Map<String, String> headers) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String url = base + TelemetryGen.TRACES_PATH;
        return payload -> {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json");
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    builder.header(header.getKey(), header.getValue());
                }
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload));
                HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // fail-open: telemetry export must never surface to the caller.
            }
        };
    }
}
