package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Batch;
import com.aktagon.llmkit.providers.generated.BatchHandle;
import com.aktagon.llmkit.providers.generated.Caching;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Batch execution mode (ADR-064) — a port of Swift's {@code Batch} / Rust's
 * {@code batch.rs} onto the shared Job engine (ADR-062). {@code Text.batch}
 * submits and returns a {@link BatchJob}; the job polls the lifecycle to
 * completion and returns the ordered responses.
 */
final class Batching {
    private Batching() {}

    /** Submit a batch of single-turn prompts and return the live {@link BatchJob}. */
    static BatchJob submit(
            Providers.Spec config,
            String apiKey,
            HttpTransport http,
            String baseUrlOverride,
            String model,
            String system,
            List<String> prompts,
            List<InputImage> images,
            List<FileRef> files,
            List<Msg> history,
            PromptOptions options) {
        Batch.Def batch = Batch.config(config.name);
        if (batch == null) {
            throw new ValidationException("provider", "batching not supported: " + config.slug);
        }
        Caching.ResourceLifecycleDef lifecycle = batch.lifecycle;
        if (lifecycle == null) {
            throw new ValidationException("provider", "async batching not supported: " + config.slug);
        }
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

        JsonObject body = Json.object();
        switch (batch.inputMode) {
            case FILE_REFERENCE_INPUT -> {
                byte[] jsonl = buildJsonl(
                        prompts, config, apiKey, model, system, images, files, history, batch, options, http, baseUrlOverride);
                String fileId = uploadFile(base, headers, batch, jsonl, http, config);
                body.addProperty(batch.inputField, fileId);
                body.addProperty("endpoint", batch.endpointPath);
                body.addProperty("completion_window", batch.completionWindow);
            }
            case INLINE_REQUESTS -> {
                JsonArray items = new JsonArray();
                // The per-item bodies may require a contract-bearing
                // anthropic-beta (structured output / files) that
                // buildAuthHeaders does not set — compose it across items and
                // ride it onto the batch CREATE request, else a
                // schema-carrying item silently drops the beta and the
                // provider 400s (mirror of Rust batch.rs build_batch_body).
                String beta = "";
                for (int index = 0; index < prompts.size(); index++) {
                    RequestBuilder.Built built = RequestBuilder.buildBody(
                            config, config.chatWireShape, apiKey, model, system,
                            itemMsgs(prompts.get(index), images, files, history), List.of(), options);
                    CachingRuntime.apply(built.body(), config, model, apiKey, options, http, baseUrlOverride);
                    String itemBeta = built.headers().get("anthropic-beta");
                    if (itemBeta != null) {
                        beta = RequestBuilder.appendBeta(beta, itemBeta);
                    }
                    if (batch.itemBodyField.isEmpty()) {
                        items.add(built.body());
                    } else {
                        JsonObject item = new JsonObject();
                        item.addProperty("custom_id", Batch.BATCH_REQUEST_ID_PREFIX + index);
                        item.add(batch.itemBodyField, built.body());
                        items.add(item);
                    }
                }
                if (!beta.isEmpty()) {
                    String existing = headers.get("anthropic-beta");
                    headers.put(
                            "anthropic-beta",
                            existing != null ? RequestBuilder.appendBeta(existing, beta) : beta);
                }
                body.add(batch.requestWrapper.isEmpty() ? "requests" : batch.requestWrapper, items);
            }
        }

        String url = base + lifecycle.createEndpoint;
        HttpTransport.Result result = http.postJson(url, Json.serialize(body), headers);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw ResponseCodec.parseError(config, result.statusCode(), result.body());
        }
        JsonElement parsed = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        String batchId = Json.stringAt(parsed, lifecycle.responseIdPath);
        if (batchId.isEmpty()) {
            throw new DecodingException("batch create: empty batch ID");
        }
        BatchHandle handle = new BatchHandle(batchId, config.name, options.raw);
        return new BatchJob(handle, apiKey, http, baseUrlOverride);
    }

    /**
     * The per-item user turn — a media turn when the builder carried
     * image/file parts (ADR-060), else a plain text turn. Batch applies the
     * builder's media to every item (the ADR-064 {@code batch(prompts...)}
     * shape carries builder-level config uniformly). The builder's history
     * precedes the user turn in every item.
     */
    private static List<Msg> itemMsgs(
            String prompt, List<InputImage> images, List<FileRef> files, List<Msg> history) {
        List<Msg> msgs = new java.util.ArrayList<>(history);
        if (images.isEmpty() && files.isEmpty()) {
            msgs.add(new Msg.Text("user", prompt));
        } else {
            msgs.add(new Msg.Media("user", prompt, images, files));
        }
        return msgs;
    }

    private static byte[] buildJsonl(
            List<String> prompts,
            Providers.Spec config,
            String apiKey,
            String model,
            String system,
            List<InputImage> images,
            List<FileRef> files,
            List<Msg> history,
            Batch.Def batch,
            PromptOptions options,
            HttpTransport http,
            String baseUrlOverride) {
        StringBuilder lines = new StringBuilder();
        for (int index = 0; index < prompts.size(); index++) {
            RequestBuilder.Built built = RequestBuilder.buildBody(
                    config, config.chatWireShape, apiKey, model, system,
                    itemMsgs(prompts.get(index), images, files, history), List.of(), options);
            CachingRuntime.apply(built.body(), config, model, apiKey, options, http, baseUrlOverride);
            JsonObject line = new JsonObject();
            line.addProperty("custom_id", Batch.BATCH_REQUEST_ID_PREFIX + index);
            line.addProperty("method", "POST");
            line.addProperty("url", batch.endpointPath);
            line.add("body", built.body());
            lines.append(Json.serialize(line)).append('\n');
        }
        return lines.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String uploadFile(
            String base,
            Map<String, String> headers,
            Batch.Def batch,
            byte[] data,
            HttpTransport http,
            Providers.Spec config) {
        HttpTransport.Result result = http.postMultipart(
                base + "/v1/files",
                Map.of("purpose", batch.filePurpose),
                "file",
                "batch_input.jsonl",
                "application/octet-stream",
                data,
                headers);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new ApiException(
                    "batch_file_upload",
                    result.statusCode(),
                    new String(result.body(), StandardCharsets.UTF_8));
        }
        JsonElement parsed = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        String fileId = Json.stringAt(parsed, "id");
        if (fileId.isEmpty()) {
            throw new DecodingException("batch file upload: empty file ID");
        }
        return fileId;
    }

    /** Binds the batch capability to the Job engine's four seams. */
    static final class BatchAdapter implements Job.Adapter<List<Response>> {
        final Job.LifecycleConfig lc;
        private final Providers.Spec spec;
        private final String base;
        private final Map<String, String> headers;
        private final Batch.Def batch;
        private final Caching.ResourceLifecycleDef lifecycle;
        private final String pollUrl;
        private final HttpTransport http;
        private final boolean raw;

        BatchAdapter(
                ProviderName provider,
                String apiKey,
                HttpTransport http,
                String baseUrlOverride,
                String id,
                boolean raw) {
            Providers.Spec config = Providers.config(provider);
            Batch.Def batch = Batch.config(provider);
            if (batch == null) {
                throw new ValidationException("provider", "batching not supported: " + config.slug);
            }
            Caching.ResourceLifecycleDef lifecycle = batch.lifecycle;
            if (lifecycle == null) {
                throw new ValidationException("provider", "async batching not supported: " + config.slug);
            }
            String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;

            this.spec = config;
            this.base = base;
            this.headers = RequestBuilder.buildAuthHeaders(config, apiKey);
            this.batch = batch;
            this.lifecycle = lifecycle;
            this.pollUrl = lifecycle.pollingEndpoint.isEmpty()
                    ? base + lifecycle.createEndpoint + "/" + id
                    : base + lifecycle.pollingEndpoint.replace("{id}", id);
            this.http = http;
            this.raw = raw;
            this.lc = new Job.LifecycleConfig();
            lc.noun = "batch";
            lc.provider = config.slug;
            lc.id = id;
            lc.statusPath = lifecycle.pollingStatusPath;
            lc.doneValues = Job.nonEmptyValues(List.of(lifecycle.pollingDoneValue));
            lc.errorValues = Job.nonEmptyValues(lifecycle.pollingErrorValues);
            lc.errorMessagePath = "";
            lc.pollIntervalMillis = 2_000;
            lc.pollTimeoutMillis = 600_000;
        }

        @Override
        public Job.LifecycleConfig config() {
            return lc;
        }

        @Override
        public Job.PollBody poll() {
            HttpTransport.Result result = http.getText(pollUrl, headers);
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw ResponseCodec.parseError(spec, result.statusCode(), result.body());
            }
            return new Job.PollBody(Json.parse(new String(result.body(), StandardCharsets.UTF_8)));
        }

        @Override
        public Job.Classification classify(Job.PollBody body) {
            return Job.classifyByConfig(lc, body);
        }

        /**
         * Reads every result source the lifecycle declares (HANDOFF-078), in
         * order: the direct result endpoint (Anthropic), then the output file
         * and the error file named in the status body (OpenAI). A file whose id
         * is absent is skipped; the call fails only when no source was read.
         * The status body is the already-decoded poll body (ADR-062 S1), so no
         * second status GET is needed for the file ids or the request count.
         */
        @Override
        public List<Response> result(Job.PollBody body) {
            List<String> sources = new ArrayList<>();
            if (!lifecycle.resultEndpoint.isEmpty()) {
                sources.add(fetch(base + lifecycle.resultEndpoint.replace("{id}", lc.id)));
            }
            for (String idPath : List.of(lifecycle.resultFileIdPath, lifecycle.errorFileIdPath)) {
                if (idPath.isEmpty()) {
                    continue;
                }
                String fileId = Json.stringAt(body.raw(), idPath);
                if (fileId.isEmpty()) {
                    continue;
                }
                sources.add(fetch(base + lifecycle.fileContentEndpoint.replace("{id}", fileId)));
            }
            if (sources.isEmpty()) {
                throw new DecodingException(
                        "batch results: no result source for " + spec.slug + " batch " + lc.id);
            }
            return parseResults(
                    spec.name, sources, batch, raw, requestCount(body.raw(), batch.requestCountPaths));
        }

        private String fetch(String url) {
            HttpTransport.Result result = http.getText(url, headers);
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw ResponseCodec.parseError(spec, result.statusCode(), result.body());
            }
            return new String(result.body(), StandardCharsets.UTF_8);
        }

        /**
         * Sums the numbers at {@code paths} in the status body. Null when no
         * path resolves to a number: there is no count.
         */
        static Integer requestCount(JsonElement status, List<String> paths) {
            Integer total = null;
            for (String path : paths) {
                JsonElement found = Json.at(status, path);
                if (found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isNumber()) {
                    total = (total == null ? 0 : total) + found.getAsInt();
                }
            }
            return total;
        }

        /** One parsed result line waiting for its index. */
        private record Slot(Response response, boolean succeeded) {}

        /**
         * Parses JSONL result sources into one Response per submitted request,
         * at that request's index (BUG-072, HANDOFF-078).
         *
         * <p>Providers return result lines in any order, so a line is placed by
         * the request id at {@code resultKeyPath}: the generated
         * {@link Batch#BATCH_REQUEST_ID_PREFIX} + N goes to index N. When one
         * index appears twice, a line that succeeded replaces a failed one, a
         * failed line never replaces a succeeded one, and a second line of the
         * same class follows the indexed slots.
         *
         * <p>With a request {@code count}, there are exactly count slots, and an
         * id at or above the count follows them. Without one (null), slots run
         * to the highest index seen. An index with no line reads
         * {@link Batch#BATCH_SLOT_MISSING}. Lines whose id has another form (a
         * batch created outside llmkit) follow the indexed slots in source
         * order. A line that is not JSON cannot be placed and is skipped.
         *
         * <p>When {@code raw} is on, a succeeded Response carries its body as
         * raw and a failed Response carries the whole line; a missing slot has
         * none.
         */
        static List<Response> parseResults(
                ProviderName provider, List<String> sources, Batch.Def batch, boolean raw, Integer count) {
            List<Slot> slots = new ArrayList<>();
            if (count != null) {
                for (int i = 0; i < count; i++) {
                    slots.add(null);
                }
            }
            List<Response> unkeyed = new ArrayList<>();
            for (String data : sources) {
                for (String rawLine : data.split("\n")) {
                    String line = rawLine.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    JsonElement wrapper;
                    try {
                        wrapper = Json.parse(line);
                    } catch (DecodingException e) {
                        continue;
                    }
                    Slot slot = parseResultLine(provider, line, wrapper, batch, raw);

                    int index = batch.resultKeyPath.isEmpty()
                            ? -1
                            : requestIndex(Json.stringAt(wrapper, batch.resultKeyPath));
                    if (index < 0 || (count != null && index >= count)) {
                        unkeyed.add(slot.response());
                        continue;
                    }
                    while (slots.size() <= index) {
                        slots.add(null);
                    }
                    Slot existing = slots.get(index);
                    if (existing == null || (slot.succeeded() && !existing.succeeded())) {
                        slots.set(index, slot);
                    } else if (existing.succeeded() && !slot.succeeded()) {
                        // The request succeeded; a failed duplicate adds nothing.
                    } else {
                        unkeyed.add(slot.response());
                    }
                }
            }

            List<Response> responses = new ArrayList<>(slots.size() + unkeyed.size());
            for (Slot slot : slots) {
                responses.add(slot != null ? slot.response() : failed(Batch.BATCH_SLOT_MISSING, null));
            }
            responses.addAll(unkeyed);
            return responses;
        }

        /**
         * Decodes one result line. The line succeeded when the value at
         * {@code resultStatusPath} is one of {@code resultSuccessValues} (any
         * value when the provider declares no status path) and its body
         * decodes. Every other line becomes a failed Response: empty text, the
         * first reason path that resolves as finishReason
         * ({@link Batch#BATCH_SLOT_ERROR} when none does) and the first message
         * path that resolves as finishMessage.
         */
        private static Slot parseResultLine(
                ProviderName provider, String line, JsonElement wrapper, Batch.Def batch, boolean raw) {
            boolean signalled = batch.resultStatusPath.isEmpty()
                    || batch.resultSuccessValues.contains(Json.stringAt(wrapper, batch.resultStatusPath));
            if (signalled) {
                // VERBATIM, not parse-navigate-re-serialize: the inner body is
                // what ADR-085 captures the assistant turn from, and serializing
                // a parsed tree emits Gson's rendering, not the provider's.
                String responseText = batch.resultBodyPath.isEmpty()
                        ? line
                        : ProviderTurnCapture.extractRawJsonPath(line, batch.resultBodyPath);
                if (responseText != null && responseText.startsWith("{")) {
                    try {
                        // Batch is Chat-Completions-only (ADR-055): an empty
                        // wire shape selects the provider's declared response
                        // paths, not the Responses output[] arm.
                        Response response = ResponseCodec.decodeResponseRaw(
                                provider, "", responseText.getBytes(StandardCharsets.UTF_8), raw);
                        return new Slot(response, true);
                    } catch (DecodingException e) {
                        // Falls through to a failed Response.
                    }
                }
            }

            String reason = firstPath(wrapper, batch.resultReasonPaths);
            Response failed = failed(reason != null ? reason : Batch.BATCH_SLOT_ERROR,
                    firstPath(wrapper, batch.resultMessagePaths));
            return new Slot(
                    ResponseCodec.attachRaw(failed, line.getBytes(StandardCharsets.UTF_8), raw), false);
        }

        /** The value at the first path that resolves to a non-empty string, or null. */
        private static String firstPath(JsonElement data, List<String> paths) {
            for (String path : paths) {
                String value = Json.optString(data, path);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        private static Response failed(String finishReason, String finishMessage) {
            return new Response(
                    "", new Usage(null, null, null, null, null, null), finishReason, finishMessage, null, null);
        }

        /**
         * Reads N out of the {@link Batch#BATCH_REQUEST_ID_PREFIX} + N id the
         * SDK sends with request N. Any other id reports -1.
         */
        private static int requestIndex(String id) {
            String prefix = Batch.BATCH_REQUEST_ID_PREFIX;
            if (!id.startsWith(prefix) || id.length() == prefix.length()) {
                return -1;
            }
            String digits = id.substring(prefix.length());
            for (int i = 0; i < digits.length(); i++) {
                char c = digits.charAt(i);
                if (c < '0' || c > '9') {
                    return -1;
                }
            }
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}
