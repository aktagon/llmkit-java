package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.MediaRef;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.TranscriptSegment;
import com.aktagon.llmkit.providers.generated.TranscriptionGen;
import com.aktagon.llmkit.providers.generated.TranscriptionHandle;
import com.aktagon.llmkit.providers.generated.TranscriptionResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*






















*/
public final class Transcription {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final String model;

    private Transcription(
            ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http, String model) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.model = model;
    }

    static Transcription root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Transcription(provider, apiKey, baseUrlOverride, http, null);
    }

    /**/
    public Transcription model(String model) {
        return new Transcription(provider, apiKey, baseUrlOverride, http, model);
    }

    //

    /*






*/
    public TranscriptionJob submit(List<Part> parts) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ValidationException("api_key", "required");
        }
        Providers.Spec config = Providers.config(provider);
        TranscriptionGen.Def tcCfg = TranscriptionGen.config(provider);
        if (tcCfg == null) {
            throw new ValidationException("provider", config.slug + " does not support transcription");
        }
        //
        //
        if ("sync".equals(tcCfg.interaction)) {
            throw new ValidationException(
                    "interaction", config.slug + " transcribes synchronously; use transcribe, not submit");
        }

        AudioSource source = normalizeAudioPart(parts);
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

        //
        //
        String audioUrl;
        if (source.bytes() != null) {
            if (tcCfg.uploadEndpoint.isEmpty()) {
                throw new ValidationException(
                        "parts", config.slug + " does not accept audio bytes; pass a public audio URL");
            }
            HttpTransport.Result uploadResult =
                    http.postBytes(base + tcCfg.uploadEndpoint, source.bytes(), headers);
            if (uploadResult.statusCode() < 200 || uploadResult.statusCode() >= 300) {
                throw new ApiException(
                        "transcription_upload", uploadResult.statusCode(),
                        new String(uploadResult.body(), StandardCharsets.UTF_8));
            }
            JsonElement uploaded = Json.parse(new String(uploadResult.body(), StandardCharsets.UTF_8));
            String uploadedUrl = Json.stringAt(uploaded, "upload_url");
            if (uploadedUrl.isEmpty()) {
                throw new DecodingException("transcription upload: response carried no upload_url");
            }
            audioUrl = uploadedUrl;
        } else {
            audioUrl = source.url();
        }

        JsonObject body = new JsonObject();
        body.addProperty("audio_url", audioUrl);
        HttpTransport.Result result = http.postJson(base + tcCfg.submitEndpoint, Json.serialize(body), headers);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new ApiException(
                    "transcription_submit", result.statusCode(), new String(result.body(), StandardCharsets.UTF_8));
        }
        JsonElement parsed = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        String id = Json.stringAt(parsed, tcCfg.submitHandleField);
        if (id.isEmpty()) {
            throw new DecodingException(
                    "transcription submit: empty handle field \"" + tcCfg.submitHandleField + "\"");
        }
        TranscriptionHandle handle = new TranscriptionHandle(id, provider);
        return new TranscriptionJob(handle, apiKey, http, baseUrlOverride);
    }

    //

    /*






*/
    public TranscriptionResponse transcribe(List<Part> parts) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new ValidationException("api_key", "required");
        }
        Providers.Spec config = Providers.config(provider);
        TranscriptionGen.Def tcCfg = TranscriptionGen.config(provider);
        if (tcCfg == null) {
            throw new ValidationException("provider", config.slug + " does not support transcription");
        }
        if (!"sync".equals(tcCfg.interaction)) {
            throw new ValidationException(
                    "interaction", config.slug + " transcribes asynchronously; use submit, not transcribe");
        }
        if (model == null || model.isEmpty()) {
            throw new ValidationException("model", "required for synchronous transcription");
        }
        MediaRef media = normalizeAudioBytesPart(parts);

        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

        //
        //
        //
        //
        String mime = media.mimeType().isEmpty() ? "application/octet-stream" : media.mimeType();
        String filename = "audio." + audioExtForMime(media.mimeType());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", model);
        fields.put("response_format", "verbose_json");
        HttpTransport.Result result = http.postMultipart(
                base + tcCfg.submitEndpoint, fields, "file", filename, mime, media.bytes(), headers);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new ApiException(config.slug, result.statusCode(), new String(result.body(), StandardCharsets.UTF_8));
        }
        JsonElement raw = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        return resultFromOpenAI(raw);
    }

    //

    private record AudioSource(String url, byte[] bytes) {}

    /*


*/
    private static AudioSource normalizeAudioPart(List<Part> parts) {
        String url = "";
        byte[] bytes = null;
        int audioCount = 0;
        for (Part part : parts) {
            if (part instanceof Part.Audio a) {
                audioCount++;
                url = a.url();
            } else if (part instanceof Part.AudioData a) {
                audioCount++;
                bytes = a.media().bytes();
            } else {
                throw new ValidationException(
                        "parts", "transcription accepts only audio parts (audio / audio_bytes)");
            }
        }
        if (audioCount != 1) {
            throw new ValidationException("parts", "transcription requires exactly one audio part");
        }
        return new AudioSource(url, bytes);
    }

    /*




*/
    private static MediaRef normalizeAudioBytesPart(List<Part> parts) {
        MediaRef media = null;
        int audioCount = 0;
        for (Part part : parts) {
            if (part instanceof Part.AudioData a) {
                audioCount++;
                media = a.media();
            } else if (part instanceof Part.Audio) {
                throw new ValidationException(
                        "parts",
                        "synchronous transcription accepts inline audio bytes only (audio_bytes); "
                                + "a remote audio URL is not supported");
            } else {
                throw new ValidationException("parts", "transcription accepts only audio parts (audio_bytes)");
            }
        }
        if (media == null || audioCount != 1) {
            throw new ValidationException("parts", "transcription requires exactly one audio part");
        }
        return media;
    }

    //

    /*





*/
    static TranscriptionResponse resultFromOpenAI(JsonElement raw) {
        String text = Json.stringAt(raw, "text");
        List<TranscriptSegment> segments = new ArrayList<>();
        JsonElement segs = Json.at(raw, "segments");
        if (segs != null && segs.isJsonArray()) {
            for (JsonElement item : segs.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                long start = Math.round(Json.doubleAt(item, "start") * 1000);
                long end = Math.round(Json.doubleAt(item, "end") * 1000);
                segments.add(new TranscriptSegment(Json.stringAt(item, "text"), start, end, ""));
            }
        }
        return new TranscriptionResponse(text, segments, Usage.zero());
    }

    /*





*/
    static TranscriptionResponse resultFromAssemblyAI(JsonElement raw) {
        String text = Json.stringAt(raw, "text");
        List<TranscriptSegment> segments = new ArrayList<>();
        JsonElement words = Json.at(raw, "words");
        if (words != null && words.isJsonArray()) {
            for (JsonElement word : words.getAsJsonArray()) {
                if (!word.isJsonObject()) {
                    continue;
                }
                segments.add(new TranscriptSegment(
                        Json.stringAt(word, "text"), Json.longAt(word, "start"), Json.longAt(word, "end"),
                        Json.stringAt(word, "speaker")));
            }
        }
        return new TranscriptionResponse(text, segments, Usage.zero());
    }

    /*



*/
    static TranscriptionResponse result(TranscriptionGen.Def tcCfg, JsonElement raw) {
        return switch (tcCfg.wireShape) {
            case "TranscriptionAssemblyAI" -> resultFromAssemblyAI(raw);
            default -> throw new IllegalStateException(
                    "transcription: unsupported wire shape \"" + tcCfg.wireShape + "\"");
        };
    }

    /*


*/
    static String audioExtForMime(String mime) {
        return switch (mime) {
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a";
            case "audio/webm" -> "webm";
            case "audio/ogg", "audio/opus" -> "ogg";
            case "audio/flac" -> "flac";
            default -> "bin";
        };
    }

    /**/
    static final class TranscriptionAdapter implements Job.Adapter<TranscriptionResponse> {
        final Job.LifecycleConfig lc;
        private final TranscriptionGen.Def tcCfg;
        private final Map<String, String> headers;
        private final String pollUrl;
        private final HttpTransport http;

        TranscriptionAdapter(
                ProviderName provider, String apiKey, HttpTransport http, String baseUrlOverride, String id) {
            Providers.Spec config = Providers.config(provider);
            TranscriptionGen.Def tcCfg = TranscriptionGen.config(provider);
            if (tcCfg == null) {
                throw new ValidationException("provider", config.slug + " does not support transcription");
            }
            String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
            Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);
            String pollUrl = base + tcCfg.pollEndpoint.replace("{id}", id);

            this.tcCfg = tcCfg;
            this.headers = headers;
            this.pollUrl = pollUrl;
            this.http = http;
            this.lc = new Job.LifecycleConfig();
            lc.noun = "transcription";
            lc.provider = config.slug;
            lc.id = id;
            lc.statusPath = tcCfg.statusPath;
            lc.doneValues = Job.nonEmptyValues(List.of(tcCfg.doneStatus));
            lc.errorValues = Job.nonEmptyValues(List.of(tcCfg.errorStatus));
            lc.errorMessagePath = config.errorMessagePath;
            lc.pollIntervalMillis = 3_000;
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
                throw new ApiException(
                        "transcription_poll", result.statusCode(), new String(result.body(), StandardCharsets.UTF_8));
            }
            return new Job.PollBody(Json.parse(new String(result.body(), StandardCharsets.UTF_8)));
        }

        @Override
        public Job.Classification classify(Job.PollBody body) {
            return Job.classifyByConfig(lc, body);
        }

        @Override
        public TranscriptionResponse result(Job.PollBody body) {
            return Transcription.result(tcCfg, body.raw());
        }
    }
}
