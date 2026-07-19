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

/*







*/
final class VideoPoll {
    private VideoPoll() {}

    /*



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

    /*



*/
    static String appendQueryAuth(String url, Providers.Spec config, String apiKey) {
        if (!"QueryParamKey".equals(config.authScheme) || config.authQueryParam.isEmpty()) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + config.authQueryParam + "=" + apiKey;
    }

    /*



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

    /*




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
                //
                //
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
                //
                String status = Json.stringAt(raw, "status");
                yield "Success".equals(status)
                        ? succeeded(status)
                        : "Fail".equals(status) ? failed(status, "") : running(status);
            }
            case "VideoVeo", "VideoVertexVeo" -> {
                //
                //
                //
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

    /*


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
                byte[] decoded;
                try {
                    decoded = java.util.Base64.getDecoder().decode(b64);
                } catch (IllegalArgumentException e) {
                    throw new DecodingException("invalid base64 in video bytesBase64Encoded: " + e.getMessage(), e);
                }
                yield new VideoResponse(
                        List.of(new VideoData(vmime, "", decoded, 0)), Usage.zero(), "", "", null);
            }
            case "VideoMinimax" -> resolveFile(vgCfg, raw, base, headers, http, mime);
            default -> emptyResponse();
        };
    }

    /*


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

    //

    private static String fallbackMime(VideoGenDef vgCfg) {
        return vgCfg.models().isEmpty() ? "video/mp4" : vgCfg.models().get(0).outputMime();
    }

    private static VideoResponse single(String mime, String url, long duration) {
        return new VideoResponse(
                List.of(new VideoData(mime, url, new byte[0], duration)), Usage.zero(), "", "", null);
    }

    /*


*/
    private static VideoResponse urlResult(String mime, String url) {
        return url.isEmpty() ? emptyResponse() : single(mime, url, 0);
    }

    /**/
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

    /**/
    static final class VideoAdapter implements Job.Adapter<VideoResponse> {
        final Job.LifecycleConfig lc;
        private final Providers.Spec spec;
        private final VideoGenDef vgCfg;
        private final String base;
        private final Map<String, String> headers;
        private final boolean raw;
        private final HttpTransport http;
        private final String apiKey;

        /**/
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
            //
            //
            //
            if ("VideoPixVerse".equals(vgCfg.wireShape())) {
                headers.put("Ai-trace-id", Video.newTraceId());
            }

            //
            //
            //
            //
            PollArm arm;
            String pollUrl;
            if ("SigV4".equals(config.authScheme)) {
                arm = PollArm.SIGV4;
                //
                //
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
                    Map<String, String> merged = RequestBuilder.sigV4Headers(
                            spec, "GET", pollUrl, new byte[0], "", apiKey, headers);
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
            //
            //
            //
            //
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

        /*



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
