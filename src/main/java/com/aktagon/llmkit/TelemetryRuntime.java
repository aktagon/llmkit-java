package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.TelemetryGen;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The middleware wiring + the pure OTLP-payload builder. Package-private —
 * {@link Telemetry} is the public config surface; this is invoked only from
 * {@link Client#addTelemetry} and exercised directly by the telemetry-wire
 * driver test.
 */
final class TelemetryRuntime {
    private TelemetryRuntime() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Builds the hook installed on the middleware seam. On the post phase it
     * renders the OTLP payload and calls {@code export}; the pre phase is a
     * no-op. Fail-open: an export error never surfaces to the caller.
     */
    static MiddlewareFn makeMiddleware(Telemetry telemetry) {
        return event -> {
            if (event.phase() != MiddlewarePhase.POST) {
                return null;
            }
            try {
                telemetry.export().accept(buildPayload(event).getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                // fail-open (ADR-059 TEL-017).
            }
            return null;
        };
    }

    /**
     * Classifies the post-phase {@link Event} and renders it to OTLP traces
     * JSON. Span identity + timing are stamped here (the pure builder takes
     * them as arguments so the parity goldens can inject fixed values).
     */
    static String buildPayload(Event event) {
        String op = TelemetryGen.operationName(event.op());
        if (op == null) {
            op = event.op().label();
        }
        long input = event.usage() != null ? event.usage().input() : 0;
        long output = event.usage() != null ? event.usage().output() : 0;
        String errorType = event.err() != null ? classifyError(event.err()) : "";
        String now = String.valueOf(Math.max(0, System.currentTimeMillis()) * 1_000_000L);
        return buildOTLPTraces(
                op, event.provider(), event.model(), input, output, errorType,
                randHex(16), randHex(8), now, now);
    }

    /**
     * The PURE, deterministic OTLP-payload builder (OTLP/HTTP, proto3-JSON).
     * Given the call's primitives plus injectable span identity + timing,
     * returns the exact JSON the exporter POSTs. The parity fixtures call it
     * with fixed inputs so all six SDKs are asserted value-identical
     * (TEL-011).
     *
     * <p>Encoding notes (OTLP/JSON spec): int64 fields (times, token counts)
     * render as strings; traceId/spanId are hex; each attribute value carries
     * exactly one of stringValue XOR intValue; the span status is present
     * only on error (code 2), omitted on success.
     */
    static String buildOTLPTraces(
            String operationName, String provider, String model,
            long inputTokens, long outputTokens, String errorType,
            String traceId, String spanId, String startNano, String endNano) {
        JsonArray attributes = new JsonArray();
        attributes.add(stringAttr(TelemetryGen.OTEL_ATTR_OP, operationName));
        attributes.add(stringAttr(TelemetryGen.OTEL_ATTR_PROVIDER, provider));
        attributes.add(stringAttr(TelemetryGen.OTEL_ATTR_MODEL, model));
        if (inputTokens > 0) {
            attributes.add(intAttr(TelemetryGen.OTEL_USAGE_INPUT, inputTokens));
        }
        if (outputTokens > 0) {
            attributes.add(intAttr(TelemetryGen.OTEL_USAGE_OUTPUT, outputTokens));
        }
        boolean hasError = errorType != null && !errorType.isEmpty();
        if (hasError) {
            attributes.add(stringAttr(TelemetryGen.OTEL_ATTR_ERR, errorType));
        }

        JsonObject span = new JsonObject();
        span.addProperty("traceId", traceId);
        span.addProperty("spanId", spanId);
        span.addProperty("name", operationName + " " + model);
        span.addProperty("kind", 3);
        span.addProperty("startTimeUnixNano", startNano);
        span.addProperty("endTimeUnixNano", endNano);
        span.add("attributes", attributes);
        if (hasError) {
            JsonObject status = new JsonObject();
            status.addProperty("code", 2);
            span.add("status", status);
        }

        JsonArray spans = new JsonArray();
        spans.add(span);
        JsonObject scope = new JsonObject();
        scope.addProperty("name", "llmkit");
        scope.addProperty("version", TelemetryGen.SEMCONV_VERSION);
        JsonObject scopeSpans = new JsonObject();
        scopeSpans.add("scope", scope);
        scopeSpans.add("spans", spans);
        JsonArray scopeSpansArray = new JsonArray();
        scopeSpansArray.add(scopeSpans);

        JsonArray resourceAttributes = new JsonArray();
        resourceAttributes.add(stringAttr("service.name", "llmkit"));
        JsonObject resource = new JsonObject();
        resource.add("attributes", resourceAttributes);

        JsonObject resourceSpans = new JsonObject();
        resourceSpans.add("resource", resource);
        resourceSpans.add("scopeSpans", scopeSpansArray);
        JsonArray resourceSpansArray = new JsonArray();
        resourceSpansArray.add(resourceSpans);

        JsonObject payload = new JsonObject();
        payload.add("resourceSpans", resourceSpansArray);
        return Json.serialize(payload);
    }

    private static JsonObject stringAttr(String key, String value) {
        JsonObject valueObj = new JsonObject();
        valueObj.addProperty("stringValue", value);
        JsonObject attr = new JsonObject();
        attr.addProperty("key", key);
        attr.add("value", valueObj);
        return attr;
    }

    /** int64 attributes render as a *string* intValue per the OTLP/JSON spec. */
    private static JsonObject intAttr(String key, long value) {
        JsonObject valueObj = new JsonObject();
        valueObj.addProperty("intValue", String.valueOf(value));
        JsonObject attr = new JsonObject();
        attr.addProperty("key", key);
        attr.add("value", valueObj);
        return attr;
    }

    /**
     * Maps a lossy {@code Event.err} message to a stable OTEL {@code
     * error.type}. The typed error is erased at the middleware seam ({@code
     * Event.err} is a {@code String}), so classification keys off the
     * message prefixes {@link LlmKitException} subtypes use. Best-effort —
     * no wire golden asserts it (the rejection golden passes {@code
     * error.type} directly).
     */
    static String classifyError(String err) {
        if (err == null || err.isEmpty()) {
            return "";
        }
        if (err.startsWith("validation:")) {
            return "validation_error";
        }
        if (err.startsWith("transport:") || err.startsWith("decoding:") || err.startsWith("middleware veto:")) {
            return "error";
        }
        return "api_error";
    }

    /** A non-crypto-grade hex string of {@code nBytes} bytes for span/trace identity. */
    private static String randHex(int nBytes) {
        byte[] bytes = new byte[nBytes];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(nBytes * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
