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

/*




*/
final class Batching {
    private Batching() {}

    /**/
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
                        prompts, config, apiKey, model, system, images, files, batch, options, http, baseUrlOverride);
                String fileId = uploadFile(base, headers, batch, jsonl, http, config);
                body.addProperty(batch.inputField, fileId);
                body.addProperty("endpoint", batch.endpointPath);
                body.addProperty("completion_window", batch.completionWindow);
            }
            case INLINE_REQUESTS -> {
                JsonArray items = new JsonArray();
                //
                //
                //
                //
                //
                //
                String beta = "";
                for (int index = 0; index < prompts.size(); index++) {
                    RequestBuilder.Built built = RequestBuilder.buildBody(
                            config, config.chatWireShape, apiKey, model, system,
                            itemMsgs(prompts.get(index), images, files), List.of(), options);
                    CachingRuntime.apply(built.body(), config, model, apiKey, options, http, baseUrlOverride);
                    String itemBeta = built.headers().get("anthropic-beta");
                    if (itemBeta != null) {
                        beta = RequestBuilder.appendBeta(beta, itemBeta);
                    }
                    if (batch.itemBodyField.isEmpty()) {
                        items.add(built.body());
                    } else {
                        JsonObject item = new JsonObject();
                        item.addProperty("custom_id", "req-" + index);
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
            throw ResponseParser.parseError(config, result.statusCode(), result.body());
        }
        JsonElement parsed = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        String batchId = Json.stringAt(parsed, lifecycle.responseIdPath);
        if (batchId.isEmpty()) {
            throw new DecodingException("batch create: empty batch ID");
        }
        BatchHandle handle = new BatchHandle(batchId, config.name, false);
        return new BatchJob(handle, apiKey, http, baseUrlOverride);
    }

    /*




*/
    private static List<Msg> itemMsgs(String prompt, List<InputImage> images, List<FileRef> files) {
        if (images.isEmpty() && files.isEmpty()) {
            return List.of(new Msg.Text("user", prompt));
        }
        return List.of(new Msg.Media("user", prompt, images, files));
    }

    private static byte[] buildJsonl(
            List<String> prompts,
            Providers.Spec config,
            String apiKey,
            String model,
            String system,
            List<InputImage> images,
            List<FileRef> files,
            Batch.Def batch,
            PromptOptions options,
            HttpTransport http,
            String baseUrlOverride) {
        StringBuilder lines = new StringBuilder();
        for (int index = 0; index < prompts.size(); index++) {
            RequestBuilder.Built built = RequestBuilder.buildBody(
                    config, config.chatWireShape, apiKey, model, system,
                    itemMsgs(prompts.get(index), images, files), List.of(), options);
            CachingRuntime.apply(built.body(), config, model, apiKey, options, http, baseUrlOverride);
            JsonObject line = new JsonObject();
            line.addProperty("custom_id", "req-" + index);
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

    /**/
    static final class BatchAdapter implements Job.Adapter<List<Response>> {
        final Job.LifecycleConfig lc;
        private final Providers.Spec spec;
        private final String base;
        private final Map<String, String> headers;
        private final Batch.Def batch;
        private final Caching.ResourceLifecycleDef lifecycle;
        private final String pollUrl;
        private final HttpTransport http;

        BatchAdapter(ProviderName provider, String apiKey, HttpTransport http, String baseUrlOverride, String id) {
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
                throw ResponseParser.parseError(spec, result.statusCode(), result.body());
            }
            return new Job.PollBody(Json.parse(new String(result.body(), StandardCharsets.UTF_8)));
        }

        @Override
        public Job.Classification classify(Job.PollBody body) {
            return Job.classifyByConfig(lc, body);
        }

        @Override
        public List<Response> result(Job.PollBody body) {
            //
            //
            String responseBody;
            if (!lifecycle.resultFileIdPath.isEmpty()) {
                String fileId = Json.stringAt(body.raw(), lifecycle.resultFileIdPath);
                if (fileId.isEmpty()) {
                    throw new DecodingException("batch results: empty output file ID");
                }
                responseBody = fetch(base + lifecycle.fileContentEndpoint.replace("{id}", fileId));
            } else if (!lifecycle.resultEndpoint.isEmpty()) {
                responseBody = fetch(base + lifecycle.resultEndpoint.replace("{id}", lc.id));
            } else {
                throw new ValidationException("provider", "batch result endpoint not configured for " + spec.slug);
            }
            return parseResults(responseBody);
        }

        private String fetch(String url) {
            HttpTransport.Result result = http.getText(url, headers);
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw ResponseParser.parseError(spec, result.statusCode(), result.body());
            }
            return new String(result.body(), StandardCharsets.UTF_8);
        }

        private List<Response> parseResults(String data) {
            List<Response> responses = new ArrayList<>();
            for (String rawLine : data.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                //
                //
                //
                //
                try {
                    String responseText;
                    if (batch.resultBodyPath.isEmpty()) {
                        responseText = line;
                    } else {
                        JsonElement inner = Json.at(Json.parse(line), batch.resultBodyPath);
                        if (inner == null) {
                            continue;
                        }
                        responseText = Json.serialize(inner);
                    }
                    responses.add(ResponseParser.parse(spec, responseText.getBytes(StandardCharsets.UTF_8)));
                } catch (DecodingException e) {
                    continue;
                }
            }
            return responses;
        }
    }
}
