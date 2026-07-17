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

/**
 * Immutable, clone-on-chain builder for speech-to-text transcription
 * (ADR-068 Phase 4f, ADR-048/ADR-051) — a port of Swift's {@code
 * Transcription} / Rust's {@code builders/transcription.rs} onto the shared
 * Job engine (ADR-062). One capability, two execution shapes selected by the
 * generated {@code transcriptionConfig(provider).interaction} fact (never the
 * provider name):
 *
 * <ul>
 *   <li>{@link #submit} — ASYNCHRONOUS (AssemblyAI): POST a {@code
 *       {audio_url}} JSON body (optionally preceded by an upload hop for
 *       inline bytes), returning a live {@link TranscriptionJob} immediately;
 *       poll it to completion with {@link TranscriptionJob#await} / {@link
 *       TranscriptionJob#poll}.
 *   <li>{@link #transcribe} — SYNCHRONOUS (OpenAI, ADR-051): a single {@code
 *       multipart/form-data} POST returns the transcript directly, no job
 *       handle — the first multipart request-wire fixture in the SDK.
 * </ul>
 *
 * <p>The result decode is wire-shape-keyed (STT-005); the submit / poll /
 * status endpoints and the sync-vs-async split are config. {@link
 * TranscriptionResponse} is text-shaped, NOT a media {@code *Data} container
 * — the structural divergence from video (ADR-048).
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

    /** Select the transcription model (required by the synchronous path; the asynchronous provider infers it). */
    public Transcription model(String model) {
        return new Transcription(provider, apiKey, baseUrlOverride, http, model);
    }

    // --- Async submit (AssemblyAI) ---

    /**
     * Submit an asynchronous speech-to-text job and return the live {@link
     * TranscriptionJob}. Pre-flight rejects a synchronous provider (naming
     * {@code transcribe}) and anything other than exactly one audio Part
     * before any HTTP call (STT-003). For an audio-bytes part the runtime
     * performs the upload hop (POST the raw bytes, read {@code upload_url})
     * before submitting (STT-005).
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
        // A synchronous provider has no job handle; Submit/Wait is the wrong
        // terminal for it (ADR-051 OAA-003). Name the supported one.
        if ("sync".equals(tcCfg.interaction)) {
            throw new ValidationException(
                    "interaction", config.slug + " transcribes synchronously; use transcribe, not submit");
        }

        AudioSource source = normalizeAudioPart(parts);
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);

        // Upload hop (STT-005): a bytes part is uploaded first to obtain a URL
        // the submit body can reference. URL parts skip this entirely.
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

    // --- Sync transcribe (OpenAI) ---

    /**
     * Run a SYNCHRONOUS speech-to-text request (ADR-051): one {@code
     * multipart/form-data} POST returns the transcript directly, no job
     * handle. Pre-flight rejects a non-sync provider (naming {@code submit}),
     * a missing model, a remote audio URL (OpenAI ingests inline bytes only —
     * the inverse of AssemblyAI, OAA-005), and a non-single-audio-bytes
     * input.
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

        // Build the multipart body in FIXED field order (model, response_format,
        // file) so all six SDKs emit the same canonical descriptor (ADR-051
        // OQ-3). The file part carries the real audio mime + the
        // format-detecting extension.
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

    // --- Part normalization (pre-flight, before any HTTP call) ---

    private record AudioSource(String url, byte[] bytes) {}

    /**
     * Enforces the single-audio-part rule (STT-003) and returns the audio
     * source: a URL XOR raw bytes. Mirror of {@code normalizeAudioPart}.
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

    /**
     * Enforces the single-audio-bytes rule for the sync path (OAA-005):
     * exactly one inline-bytes audio Part. A remote URL is rejected (OpenAI
     * ingests no URL — the inverse of AssemblyAI). Mirror of {@code
     * normalizeAudioBytesPart}.
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

    // --- Result decode (wire-shape-keyed, STT-005) ---

    /**
     * Extracts the transcript text + (when present) segment timings from a
     * synchronous OpenAI response. verbose_json offsets are SECONDS (float)
     * -> integer milliseconds (x1000, rounded, OAA-006). Missing segments ->
     * empty, not an error. Usage stays zero (OAA-007). Mirror of {@code
     * transcriptionResultFromOpenAI}.
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

    /**
     * Extracts the transcript text + word-level timing from a completed
     * AssemblyAI transcript object. start/end are integer milliseconds;
     * speaker is present only on diarized transcripts. Usage stays zero —
     * AssemblyAI bills by audio duration, not tokens (ADR-048 OQ-2). Mirror
     * of {@code transcriptionResultFromAssemblyAI}.
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

    /**
     * Extracts the finished transcript per wire shape. Only the result decode
     * is wire-shape-keyed (STT-005); the submit / poll / status facts are
     * config. Mirror of {@code transcriptionResult}.
     */
    static TranscriptionResponse result(TranscriptionGen.Def tcCfg, JsonElement raw) {
        return switch (tcCfg.wireShape) {
            case "TranscriptionAssemblyAI" -> resultFromAssemblyAI(raw);
            default -> throw new IllegalStateException(
                    "transcription: unsupported wire shape \"" + tcCfg.wireShape + "\"");
        };
    }

    /**
     * Maps an audio IANA media type to the file extension OpenAI uses to
     * detect the format. Mirror of {@code audioExtForMime}.
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

    /** Binds async transcription to the Job engine's four seams (ADR-062). */
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
