package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.TelemetryGen;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/*




*/
final class TelemetryRuntime {
    private TelemetryRuntime() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /*



*/
    static MiddlewareFn makeMiddleware(Telemetry telemetry) {
        return event -> {
            if (event.phase() != MiddlewarePhase.POST) {
                return null;
            }
            try {
                telemetry.export().accept(buildPayload(event).getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                //
            }
            return null;
        };
    }

    /*





*/
    static String buildPayloadAt(Event event, String traceId, String spanId, String startNano, String endNano) {
        String op = TelemetryGen.operationName(event.op());
        if (op == null) {
            op = event.op().label();
        }
        long input = event.usage() != null ? event.usage().input() : 0;
        long output = event.usage() != null ? event.usage().output() : 0;
        String errorType = event.errType() != null ? event.errType() : "";
        return buildOTLPTraces(
                op, event.provider(), event.model(), input, output, errorType,
                traceId, spanId, startNano, endNano);
    }

    /*


*/
    static String buildPayload(Event event) {
        String now = String.valueOf(Math.max(0, System.currentTimeMillis()) * 1_000_000L);
        return buildPayloadAt(event, randHex(16), randHex(8), now, now);
    }

    /*










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
            attributes.add(stringAttr(TelemetryGen.OTEL_ATTR_ERR_TYPE, errorType));
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

    /**/
    private static JsonObject intAttr(String key, long value) {
        JsonObject valueObj = new JsonObject();
        valueObj.addProperty("intValue", String.valueOf(value));
        JsonObject attr = new JsonObject();
        attr.addProperty("key", key);
        attr.add("value", valueObj);
        return attr;
    }

    /**/
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
