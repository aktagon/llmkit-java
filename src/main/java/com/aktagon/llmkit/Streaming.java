package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import com.aktagon.llmkit.providers.generated.Stream;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Streaming (SSE) text generation — a port of Swift's {@code Streamer} /
 * Rust's {@code stream.rs}. Builds the request body through the shared
 * {@link RequestBuilder}, applies caching, adds the per-provider stream flag
 * (+ {@code stream_options.include_usage} where the provider requires it,
 * BUG-028), and consumes the {@code event:} / {@code data:} frame stream,
 * invoking {@code onDelta} per text chunk and assembling the final
 * {@code Response}. Fires the {@code llmRequest} middleware op around the
 * whole call, mirroring Go's {@code promptStream}.
 */
final class Streaming {
    private Streaming() {}

    static Response run(
            Providers.Spec config,
            String apiKey,
            String model,
            String system,
            List<Msg> msgs,
            PromptOptions options,
            HttpTransport http,
            String baseUrlOverride,
            Consumer<String> onDelta) {
        Stream.Def stream = Stream.config(config.name);
        if (stream == null) {
            throw new ValidationException("provider", "streaming not supported: " + config.slug);
        }

        Event baseEvent = Event.of(MiddlewareOp.LLM_REQUEST, config.slug, model);
        long startNanos = System.nanoTime();
        Middleware.firePre(options.middleware, baseEvent);

        try {
            Response response = doRun(config, apiKey, model, system, msgs, options, http, baseUrlOverride, onDelta, stream);
            Middleware.firePost(
                    options.middleware,
                    baseEvent.toPost("", response.usage(), null, Middleware.elapsedMillis(startNanos)));
            return response;
        } catch (RuntimeException e) {
            Middleware.firePost(
                    options.middleware,
                    baseEvent.toPost("", null, e.getMessage(), Middleware.elapsedMillis(startNanos)));
            throw e;
        }
    }

    private static Response doRun(
            Providers.Spec config,
            String apiKey,
            String model,
            String system,
            List<Msg> msgs,
            PromptOptions options,
            HttpTransport http,
            String baseUrlOverride,
            Consumer<String> onDelta,
            Stream.Def stream) {
        RequestBuilder.Built built = RequestBuilder.buildBody(
                config, config.chatWireShape, apiKey, model, system, msgs, List.of(), options);
        JsonObject body = built.body();
        CachingRuntime.apply(body, config, model, apiKey, options, http, baseUrlOverride);
        if (!stream.param.isEmpty()) {
            body.addProperty(stream.param, true);
        }
        // BUG-028: opt into a streamed usage frame where the provider requires it.
        if (stream.usageOptIn) {
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            body.add("stream_options", streamOptions);
        }

        String url = streamUrl(config, stream, apiKey, model, baseUrlOverride);
        HttpTransport.StreamResult result =
                http.postJsonStreaming(url, Json.serialize(body), built.headers());

        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            String raw;
            try (java.util.stream.Stream<String> errorLines = result.lines()) {
                raw = errorLines.collect(Collectors.joining("\n"));
            }
            throw ResponseParser.parseError(
                    config, result.statusCode(), raw.getBytes(StandardCharsets.UTF_8));
        }

        String[] finishSplit = parseStreamFinishPath(config.streamFinishReasonPath);
        String finishEvent = finishSplit[0];
        String finishPath = finishSplit[1];
        StringBuilder fullText = new StringBuilder();
        String finishReason = "";
        long usageInput = 0;
        long usageOutput = 0;
        String currentEvent = "";

        // The lines Stream owns the response subscription: close it on every
        // exit (the done-sentinel early returns fire on essentially every
        // successful stream before the body is exhausted) or the connection
        // leaks in the shared HttpClient. A mid-stream network failure
        // surfaces from the iterator as UncheckedIOException; rewrap so the
        // typed-error contract (LlmKitException) holds on this path too.
        try (java.util.stream.Stream<String> lineStream = result.lines()) {
            Iterator<String> lines = lineStream.iterator();
            while (lines.hasNext()) {
                String line = lines.next();
                String event = strip(line, "event: ");
                if (event != null) {
                    currentEvent = event;
                    continue;
                }
                String data = strip(line, "data: ");
                if (data == null) {
                    continue;
                }

                // Data-level done sentinel (e.g. OpenAI [DONE]) is literal, not JSON.
                if (!stream.doneSignal.isEmpty() && data.equals(stream.doneSignal)) {
                    return assemble(fullText.toString(), usageInput, usageOutput, finishReason);
                }

                JsonElement parsed;
                try {
                    parsed = Json.parse(data);
                } catch (DecodingException e) {
                    parsed = null;
                }

                // ADR-013: capture the stream-time finish reason before any event-
                // level done return.
                if (parsed != null && !finishPath.isEmpty()
                        && (finishEvent.isEmpty() || finishEvent.equals(currentEvent))) {
                    String value = Json.stringAt(parsed, finishPath);
                    if (!value.isEmpty() && !"FINISH_REASON_UNSPECIFIED".equals(value)) {
                        finishReason = value;
                    }
                }

                if (stream.usesEventTypes && !stream.doneEvent.isEmpty() && currentEvent.equals(stream.doneEvent)) {
                    return assemble(fullText.toString(), usageInput, usageOutput, finishReason);
                }

                if (parsed == null) {
                    currentEvent = "";
                    continue;
                }

                if (stream.usesEventTypes) {
                    if (currentEvent.equals(stream.contentEvent)) {
                        String text = Json.stringAt(parsed, stream.deltaTextPath);
                        if (!text.isEmpty()) {
                            fullText.append(text);
                            onDelta.accept(text);
                        }
                    }
                    if (currentEvent.equals(stream.usageEvent) && !stream.usageOutputPath.isEmpty()) {
                        usageOutput = Json.longAt(parsed, stream.usageOutputPath);
                        if (!stream.usageInputPath.isEmpty()) {
                            usageInput = Json.longAt(parsed, stream.usageInputPath);
                        }
                    }
                } else {
                    String text = Json.stringAt(parsed, stream.deltaTextPath);
                    if (!text.isEmpty()) {
                        fullText.append(text);
                        onDelta.accept(text);
                    }
                    if (!stream.usageInputPath.isEmpty()) {
                        long value = Json.longAt(parsed, stream.usageInputPath);
                        if (value > 0) {
                            usageInput = value;
                        }
                    }
                    if (!stream.usageOutputPath.isEmpty()) {
                        long value = Json.longAt(parsed, stream.usageOutputPath);
                        if (value > 0) {
                            usageOutput = value;
                        }
                    }
                }
                currentEvent = "";
            }

            return assemble(fullText.toString(), usageInput, usageOutput, finishReason);
        } catch (java.io.UncheckedIOException e) {
            throw new TransportException("stream interrupted: " + e.getMessage(), e);
        }
    }

    private static Response assemble(String text, long input, long output, String finishReason) {
        return new Response(text, new Usage(input, output, 0, 0, 0, 0.0), finishReason, "", null);
    }

    /**
     * Split {@code event_name:json.path} into its event-name prefix and the
     * JSON path. Bare paths return {"", path}; empty returns {"", ""}.
     */
    private static String[] parseStreamFinishPath(String p) {
        if (p.isEmpty()) {
            return new String[] {"", ""};
        }
        int idx = p.indexOf(':');
        if (idx >= 0) {
            return new String[] {p.substring(0, idx), p.substring(idx + 1)};
        }
        return new String[] {"", p};
    }

    private static String strip(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()) : null;
    }

    private static String streamUrl(
            Providers.Spec config, Stream.Def stream, String apiKey, String model, String baseUrlOverride) {
        if (stream.endpoint.isEmpty()) {
            return RequestBuilder.buildUrl(config, config.endpoint, apiKey, model, baseUrlOverride);
        }
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        if (!config.regionEnvVar.isEmpty()) {
            String region = System.getenv(config.regionEnvVar);
            if (region != null) {
                base = base.replace("{region}", region);
            }
        }
        String endpoint = stream.endpoint.replace("{model}", model).replace("{apiKey}", apiKey);
        if ("QueryParamKey".equals(config.authScheme)) {
            String separator = endpoint.contains("?") ? "&" : "?";
            endpoint += separator + config.authQueryParam + "=" + apiKey;
        }
        return base + endpoint;
    }
}
