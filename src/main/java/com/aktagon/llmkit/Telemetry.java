package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.TelemetryGen;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/*























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

    /*







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
                //
            }
        };
    }
}
