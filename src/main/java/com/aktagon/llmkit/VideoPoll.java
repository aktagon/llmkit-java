package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.VideoData;
import com.aktagon.llmkit.providers.generated.VideoGenDef;
import com.aktagon.llmkit.providers.generated.VideoResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Binds the video capability to the Job engine's four seams (ADR-062). Video's
 * poll classification is NOT config-driven the way batch's is — the generated
 * {@code VideoGenDef} carries no status paths — so {@link #classify} dispatches
 * on the {@code wireShape} fact (a port of Rust's {@code parse_video_poll}) and
 * {@link #result} extracts the finished video URL/bytes per shape (the
 * {@code video_result_from_*} family, including the MiniMax two-hop file
 * retrieve and the Veo download hop). A port of Swift's {@code VideoPoll.swift}.
 */
final class VideoPoll {
    private VideoPoll() {}

    /**
     * Video API base: an override wins, else the provider's distinct video
     * base when set, else the chat base — with {@code {region}} resolved from
     * the region env var.
     */
    static String baseUrl(Providers.Spec config, VideoGenDef vgCfg, String override) {
        if (override != null) {
            return override;
        }
        String base = vgCfg.videoBaseUrl().isEmpty() ? config.baseUrl : vgCfg.videoBaseUrl();
        if (!config.regionEnvVar.isEmpty()) {
            String region = System.getenv(config.regionEnvVar);
            if (region != null) {
                base = base.replace("{region}", region);
            }
        }
        return base;
    }

    /**
     * Appends {@code ?key=}/{@code &key=} for query-param-auth providers
     * (Google); a no-op otherwise. Picks the separator by whether the URL
     * already has a query.
     */
    static String appendQueryAuth(String url, Providers.Spec config, String apiKey) {
        if (!"QueryParamKey".equals(config.authScheme) || config.authQueryParam.isEmpty()) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + config.authQueryParam + "=" + apiKey;
    }

    /**
     * Descends a dotted path (e.g. "id", "output.task_id", "Resp.video_id")
     * through the submit response; a numeric leaf (PixVerse's integer job id)
     * is formatted back to its integer string.
     */
    static String lookupHandleField(JsonElement raw, String path) {
        if (path.isEmpty()) {
            return "";
        }
        JsonElement found = Json.at(raw, path);
        if (found == null || !found.isJsonPrimitive()) {
            return "";
        }
        JsonPrimitive primitive = found.getAsJsonPrimitive();
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        if (primitive.isNumber()) {
            return String.valueOf(primitive.getAsLong());
        }
        return "";
    }

    /**
     * Classifies one poll body per wire shape (port of {@code
     * parse_video_poll}). Returns running / succeeded / failed; an unmodeled
     * wire shape is an internal invariant violation (the switch is exhaustive
     * over the ten configured shapes), not a runtime user error.
     */
    static Job.Classification classify(VideoGenDef vgCfg, JsonElement raw) {
        return switch (vgCfg.wireShape()) {
            case "VideoQwen" -> {
                String status = Json.stringAt(raw, "output.task_status");
                yield switch (status) {
                    case "SUCCEEDED" -> succeeded(status);
                    case "FAILED", "CANCELED" -> failed(status, "");
                    default -> running(status);
                };
            }
            case "VideoTogether" -> {
                String status = Json.stringAt(raw, "status");
                yield switch (status) {
                    case "completed" -> succeeded(status);
                    case "failed", "cancelled" -> failed(status, "");
                    default -> running(status);
                };
            }
            case "VideoZhipu" -> {
                String status = Json.stringAt(raw, "task_status");
                yield "SUCCESS".equals(status)
                        ? succeeded(status)
                        : "FAIL".equals(status) ? failed(status, "") : running(status);
            }
            case "VideoVidu" -> {
                String state = Json.stringAt(raw, "state");
                if ("success".equals(state)) {
                    yield succeeded(state);
                }
                if ("failed".equals(state)) {
                    String msg = firstNonEmpty(Json.stringAt(raw, "err_code"), Json.stringAt(raw, "message"));
                    yield failed(state, msg);
                }
                yield running(state);
            }
            case "VideoPixVerse" -> {
                // Status is an INTEGER code nested under Resp: 1=success,
                // 7/8=failed, 5=generating.
                JsonElement resp = Json.at(raw, "Resp");
                long status = resp != null ? Json.longAt(resp, "status") : -1;
                if (status == 1) {
                    yield succeeded(String.valueOf(status));
                }
                if (status == 7 || status == 8) {
                    yield failed(String.valueOf(status), "");
                }
                yield running(String.valueOf(status));
            }
            case "VideoMinimax" -> {
                // Two-hop: success yields a file_id (resolved in result), not a URL.
                String status = Json.stringAt(raw, "status");
                yield "Success".equals(status)
                        ? succeeded(status)
                        : "Fail".equals(status) ? failed(status, "") : running(status);
            }
            case "VideoVeo", "VideoVertexVeo" -> {
                // Operation-based LRO: poll until done==true. A done op with an
                // error object is a terminal failure; otherwise the finished
                // video is in the response (extracted in result).
                JsonElement doneElement = Json.at(raw, "done");
                boolean done = doneElement != null
                        && doneElement.isJsonPrimitive()
                        && doneElement.getAsJsonPrimitive().isBoolean()
                        && doneElement.getAsBoolean();
                if (!done) {
                    yield running("");
                }
                JsonElement err = Json.at(raw, "error");
                if (err != null && err.isJsonObject()) {
                    yield failed("error", Json.stringAt(raw, "error.message"));
                }
                yield succeeded("done");
            }
            case "VideoBedrock" -> {
                String status = Json.stringAt(raw, "status");
                yield "Completed".equals(status)
                        ? succeeded(status)
                        : "Failed".equals(status)
                                ? failed(status, Json.stringAt(raw, "failureMessage"))
                                : running(status);
            }
            case "VideoGrok" -> {
                String status = Json.stringAt(raw, "status");
                if ("done".equals(status)) {
                    yield succeeded(status);
                }
                if ("failed".equals(status) || "expired".equals(status)) {
                    yield failed(status, Json.stringAt(raw, "error.message"));
                }
                yield running(status);
            }
            default -> throw new IllegalStateException("video poll: unsupported wire shape \"" + vgCfg.wireShape() + "\"");
        };
    }

    /**
     * Extracts the finished {@code VideoResponse} per wire shape (the {@code
     * video_result_from_*} family). Only called on a succeeded classification.
     */
    static VideoResponse result(
            VideoGenDef vgCfg, JsonElement raw, String base, Map<String, String> headers, HttpTransport http) {
        String mime = fallbackMime(vgCfg);
        return switch (vgCfg.wireShape()) {
            case "VideoGrok" -> {
                JsonElement video = Json.at(raw, "video");
                if (video == null || !video.isJsonObject()) {
                    yield emptyResponse();
                }
                yield single(mime, Json.stringAt(video, "url"), Json.longAt(video, "duration"));
            }
            case "VideoZhipu" -> urlResult(mime, Json.stringAt(raw, "video_result[0].url"));
            case "VideoVidu" -> urlResult(mime, Json.stringAt(raw, "creations[0].url"));
            case "VideoTogether" -> urlResult(mime, Json.stringAt(raw, "outputs.video_url"));
            case "VideoQwen" -> urlResult(mime, Json.stringAt(raw, "output.video_url"));
            case "VideoPixVerse" -> urlResult(mime, Json.stringAt(raw, "Resp.url"));
            case "VideoBedrock" -> {
                String url = Json.stringAt(raw, "outputDataConfig.s3OutputDataConfig.s3Uri");
                if (url.isEmpty()) {
                    throw new DecodingException("video generation: completed but carried no output s3 uri");
                }
                yield single(mime, url, 0);
            }
            case "VideoVeo" -> {
                String url = Json.stringAt(raw, "response.generateVideoResponse.generatedSamples[0].video.uri");
                if (url.isEmpty()) {
                    throw new DecodingException("video generation: operation done but carried no video uri");
                }
                yield single(mime, url, 0);
            }
            case "VideoVertexVeo" -> {
                JsonElement first = Json.at(raw, "response.videos[0]");
                String vmime = mime;
                String b64 = "";
                if (first != null && first.isJsonObject()) {
                    JsonObject firstObject = first.getAsJsonObject();
                    JsonElement echoedMime = firstObject.get("mimeType");
                    if (echoedMime != null && echoedMime.isJsonPrimitive() && !echoedMime.getAsString().isEmpty()) {
                        vmime = echoedMime.getAsString();
                    }
                    JsonElement bytesElement = firstObject.get("bytesBase64Encoded");
                    if (bytesElement != null && bytesElement.isJsonPrimitive()) {
                        b64 = bytesElement.getAsString();
                    }
                }
                if (b64.isEmpty()) {
                    throw new DecodingException("video generation: operation done but carried no video bytes");
                }
                byte[] decoded = java.util.Base64.getDecoder().decode(b64);
                yield new VideoResponse(
                        List.of(new VideoData(vmime, "", decoded, 0)), Usage.zero(), "", "", null);
            }
            case "VideoMinimax" -> resolveFile(vgCfg, raw, base, headers, http, mime);
            default -> emptyResponse();
        };
    }

    /**
     * The MiniMax file-retrieve hop: reads file_id from the terminal poll,
     * GETs the file endpoint, and extracts file.download_url.
     */
    private static VideoResponse resolveFile(
            VideoGenDef vgCfg,
            JsonElement poll,
            String base,
            Map<String, String> headers,
            HttpTransport http,
            String mime) {
        String fileId = "";
        JsonElement fileIdElement = Json.at(poll, "file_id");
        if (fileIdElement != null && fileIdElement.isJsonPrimitive()) {
            JsonPrimitive primitive = fileIdElement.getAsJsonPrimitive();
            fileId = primitive.isString() ? primitive.getAsString() : String.valueOf(primitive.getAsLong());
        }
        if (fileId.isEmpty()) {
            throw new DecodingException("video file hop: terminal poll carried no file_id");
        }
        String url = base + vgCfg.fileEndpoint().replace("{file_id}", fileId);
        HttpTransport.Result result = http.getText(url, headers);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new ApiException(
                    "video_file_retrieve", result.statusCode(), new String(result.body(), StandardCharsets.UTF_8));
        }
        JsonElement fileRaw = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        return urlResult(mime, Json.stringAt(fileRaw, "file.download_url"));
    }

    // --- Small builders ---

    private static String fallbackMime(VideoGenDef vgCfg) {
        return vgCfg.models().isEmpty() ? "video/mp4" : vgCfg.models().get(0).outputMime();
    }

    private static VideoResponse single(String mime, String url, long duration) {
        return new VideoResponse(
                List.of(new VideoData(mime, url, new byte[0], duration)), Usage.zero(), "", "", null);
    }

    /**
     * A url-delivery result, or the empty response when the URL is absent
     * (matches the Rust {@code video_result_from_*} empty-url guard).
     */
    private static VideoResponse urlResult(String mime, String url) {
        return url.isEmpty() ? emptyResponse() : single(mime, url, 0);
    }

    /** The empty video response (a failed or still-empty poll). */
    private static VideoResponse emptyResponse() {
        return new VideoResponse(List.of(), Usage.zero(), "", "", null);
    }

    private static Job.Classification succeeded(String status) {
        return new Job.Classification(JobState.SUCCEEDED, null, status);
    }

    private static Job.Classification failed(String status, String message) {
        return new Job.Classification(JobState.FAILED, new JobFailure(status, message, false), status);
    }

    private static Job.Classification running(String status) {
        return new Job.Classification(JobState.RUNNING, null, status);
    }

    private static String firstNonEmpty(String a, String b) {
        if (!a.isEmpty()) {
            return a;
        }
        return !b.isEmpty() ? b : "operation failed";
    }

    /** Binds the video capability to the Job engine's four seams. */
    static final class VideoAdapter implements Job.Adapter<VideoResponse> {
        final Job.LifecycleConfig lc;
        private final Providers.Spec spec;
        private final VideoGenDef vgCfg;
        private final String base;
        private final Map<String, String> headers;
        private final boolean raw;
        private final HttpTransport http;
        private final String apiKey;

        /** Poll transport arm, selected once before the loop (mirror of {@code wait_video}). */
        private enum PollArm {
            SIGV4, // Bedrock: SigV4-signed GET, ARN as one path segment
            VERTEX_POST, // Vertex Veo: POST {model}:fetchPredictOperation
            GET // every other provider: verbatim {id} GET
        }

        private final PollArm arm;
        private final String pollUrl;

        VideoAdapter(
                ProviderName provider,
                String apiKey,
                HttpTransport http,
                String baseUrlOverride,
                String id,
                String model,
                boolean raw) {
            Providers.Spec config = Providers.config(provider);
            VideoGenDef vgCfg = VideoGenDef.videoGenConfig(provider);
            if (vgCfg == null) {
                throw new ValidationException("provider", config.slug + " does not support video generation");
            }
            String base = VideoPoll.baseUrl(config, vgCfg, baseUrlOverride);
            Map<String, String> headers = new LinkedHashMap<>(RequestBuilder.buildAuthHeaders(config, apiKey));
            // PixVerse requires the per-request Ai-trace-id on the poll GET too;
            // one per wait call suffices (uniqueness is an anti-cache measure on
            // submit).
            if ("VideoPixVerse".equals(vgCfg.wireShape())) {
                headers.put("Ai-trace-id", Video.newTraceId());
            }

            // The arms are config-disjoint: SigV4 keys off authScheme,
            // VERTEX_POST off wireShape, and no A-Box pairs SigV4 with
            // VideoVertexVeo. SigV4 is matched first so a hypothetical
            // both-true misconfig polls as SigV4.
            PollArm arm;
            String pollUrl;
            if ("SigV4".equals(config.authScheme)) {
                arm = PollArm.SIGV4;
                // Encode the ARN's '/' to %2F (one path segment) but leave ':'
                // literal (Bedrock's SigV4 canonicalization accepts a literal ':').
                pollUrl = base + vgCfg.pollEndpoint().replace("{id}", id.replace("/", "%2F"));
            } else if ("VideoVertexVeo".equals(vgCfg.wireShape())) {
                arm = PollArm.VERTEX_POST;
                pollUrl = VideoPoll.appendQueryAuth(
                        base + vgCfg.pollEndpoint().replace("{model}", model), config, apiKey);
            } else {
                arm = PollArm.GET;
                pollUrl = VideoPoll.appendQueryAuth(base + vgCfg.pollEndpoint().replace("{id}", id), config, apiKey);
            }

            this.spec = config;
            this.vgCfg = vgCfg;
            this.base = base;
            this.headers = headers;
            this.raw = raw;
            this.http = http;
            this.apiKey = apiKey;
            this.arm = arm;
            this.pollUrl = pollUrl;
            this.lc = new Job.LifecycleConfig();
            lc.noun = "video generation";
            lc.provider = config.slug;
            lc.id = id;
            lc.statusPath = "";
            lc.doneValues = List.of();
            lc.errorValues = List.of();
            lc.errorMessagePath = "";
            lc.pollIntervalMillis = 5_000;
            lc.pollTimeoutMillis = 600_000;
        }

        @Override
        public Job.LifecycleConfig config() {
            return lc;
        }

        @Override
        public Job.PollBody poll() {
            HttpTransport.Result result;
            switch (arm) {
                case SIGV4 -> {
                    String region = System.getenv(spec.regionEnvVar);
                    if (region == null) {
                        throw new ValidationException("provider", "missing env var " + spec.regionEnvVar);
                    }
                    String secretKey = System.getenv(spec.secretKeyEnvVar);
                    if (secretKey == null) {
                        throw new ValidationException("provider", "missing env var " + spec.secretKeyEnvVar);
                    }
                    String sessionToken = spec.sessionTokenEnvVar.isEmpty()
                            ? ""
                            : Objects.requireNonNullElse(System.getenv(spec.sessionTokenEnvVar), "");
                    Map<String, String> signed = SigV4.sign(
                            "GET", pollUrl, new byte[0], apiKey, secretKey, sessionToken, region, spec.serviceName,
                            "application/json");
                    Map<String, String> merged = new LinkedHashMap<>(signed);
                    merged.putAll(headers);
                    result = http.getText(pollUrl, merged);
                }
                case VERTEX_POST -> {
                    JsonObject body = new JsonObject();
                    body.addProperty("operationName", lc.id);
                    result = http.postJson(pollUrl, Json.serialize(body), headers);
                }
                default -> result = http.getText(pollUrl, headers);
            }
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw new ApiException(
                        "video_poll", result.statusCode(), new String(result.body(), StandardCharsets.UTF_8));
            }
            return new Job.PollBody(Json.parse(new String(result.body(), StandardCharsets.UTF_8)));
        }

        @Override
        public Job.Classification classify(Job.PollBody body) {
            return VideoPoll.classify(vgCfg, body.raw());
        }

        @Override
        public VideoResponse result(Job.PollBody body) {
            VideoResponse response = VideoPoll.result(vgCfg, body.raw(), base, headers, http);
            // Download delivery (Veo): the poll result placed a temporary fetch
            // URI in VideoData.url; fetch each and fill VideoData.bytes
            // (clearing url, the source-XOR contract VID-004). Vertex is
            // inline-base64 (no url) — no-op.
            if ("DeliveryDownload".equals(vgCfg.outputDelivery())) {
                response = downloadBytes(response);
            }
            if (raw) {
                response = new VideoResponse(
                        response.videos(), response.usage(), response.finishReason(), response.finishMessage(),
                        body.raw());
            }
            return response;
        }

        /**
         * Fetches finished-video bytes for download-delivery providers,
         * carrying the query-param auth (Google {@code ?key=}) and moving the
         * payload into bytes.
         */
        private VideoResponse downloadBytes(VideoResponse input) {
            List<VideoData> updated = new ArrayList<>();
            for (VideoData video : input.videos()) {
                if (video.url().isEmpty()) {
                    updated.add(video);
                    continue;
                }
                String fetchUrl = VideoPoll.appendQueryAuth(video.url(), spec, apiKey);
                HttpTransport.Result result = http.getText(fetchUrl, headers);
                if (result.statusCode() < 200 || result.statusCode() >= 300) {
                    throw new ApiException(
                            "video_download", result.statusCode(),
                            new String(result.body(), StandardCharsets.UTF_8));
                }
                updated.add(new VideoData(video.mimeType(), "", result.body(), video.durationSeconds()));
            }
            return new VideoResponse(updated, input.usage(), input.finishReason(), input.finishMessage(), input.raw());
        }
    }
}
