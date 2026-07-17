package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Batch;
import com.aktagon.llmkit.providers.generated.Caching;
import com.aktagon.llmkit.providers.generated.ImageGenDef;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Request;
import java.util.ArrayList;
import java.util.List;

/**
 * The entry point to the SDK. Immutable; builders reached from it clone on
 * chain (ADR-068 JAVA-004: synchronous public API, mirroring the Go SDK).
 * Phase 2 exposes the {@link #text()} builder at full ChatCompletion parity
 * (options, structured output, the Responses protocol) plus the
 * {@link #supports(Capability)} capability query (ADR-030).
 */
public final class Client {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final List<MiddlewareFn> defaultMiddleware;

    /** Create a client for a provider. */
    public Client(ProviderName provider, String apiKey) {
        this(provider, apiKey, new JdkHttpTransport());
    }

    /** Transport-injecting constructor (tests supply a capturing fake). */
    Client(ProviderName provider, String apiKey, HttpTransport http) {
        this(provider, apiKey, null, http, List.of());
    }

    private Client(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            List<MiddlewareFn> defaultMiddleware) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.defaultMiddleware = defaultMiddleware;
    }

    /** Convenience constructor for OpenAI. */
    public static Client openai(String apiKey) {
        return new Client(ProviderName.OPENAI, apiKey);
    }

    /**
     * Override the provider base URL — the caller-substituted seam for
     * account/project/region-in-URL providers (ADR-035) and the test transport.
     */
    public Client baseUrl(String url) {
        return new Client(provider, apiKey, url, http, defaultMiddleware);
    }

    /**
     * Enable opt-in telemetry on this client (ADR-054/ADR-059). The export
     * hook rides the middleware seam, so every capability builder that
     * carries one (text/agent/image/music/video) emits one OTEL span on the
     * post phase. The honest contract (TEL-017) is upheld by {@link
     * Telemetry}'s constructor: an enabled-but-no-sink config cannot be
     * built. Returns a new {@code Client} for chaining.
     */
    public Client addTelemetry(Telemetry telemetry) {
        List<MiddlewareFn> next = new ArrayList<>(defaultMiddleware);
        next.add(TelemetryRuntime.makeMiddleware(telemetry));
        return new Client(provider, apiKey, baseUrlOverride, http, next);
    }

    /** The text-generation builder. */
    public Text text() {
        Text builder = Text.root(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            builder = builder.addMiddleware(hook);
        }
        return builder;
    }

    /** A fresh stateful tool-loop agent (the one stateful builder). */
    public Agent agent() {
        Agent agent = new Agent(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            agent.addMiddleware(hook);
        }
        return agent;
    }

    /** The image-generation builder. */
    public Image image() {
        Image builder = Image.root(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            builder = builder.addMiddleware(hook);
        }
        return builder;
    }

    /** The speech-generation (text-to-speech) builder. */
    public Speech speech() {
        return Speech.root(provider, apiKey, baseUrlOverride, http);
    }

    /** The music-generation builder. */
    public Music music() {
        Music builder = Music.root(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            builder = builder.addMiddleware(hook);
        }
        return builder;
    }

    /** The video-generation builder (asynchronous; {@code submit} returns a live {@link VideoJob}). */
    public Video video() {
        Video builder = Video.root(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            builder = builder.addMiddleware(hook);
        }
        return builder;
    }

    /**
     * The transcription (speech-to-text) builder (ADR-048 / ADR-051). {@code
     * submit} starts an asynchronous job and returns a live {@link
     * TranscriptionJob} (AssemblyAI); {@code transcribe} runs a synchronous
     * request and returns the transcript directly (OpenAI). The two shapes
     * dispatch on the generated transcription config, never the provider
     * name.
     */
    public Transcription transcription() {
        return Transcription.root(provider, apiKey, baseUrlOverride, http);
    }

    /**
     * The model catalogue builder (ADR-019). {@code list}/{@code get} walk
     * the compiled-in slice; {@code provider(p).list()}/{@code get(id)} and
     * {@code live()} fetch live from the provider's {@code /v1/models}
     * endpoint.
     */
    public Models models() {
        return Models.root(provider, apiKey, baseUrlOverride, http);
    }

    /**
     * The providers-namespace builder (ADR-019 / ADR-040 PSR-005). {@code
     * list()} returns the bound provider's public metadata, iff it declares a
     * live catalogue endpoint.
     */
    public Providers providers() {
        return new Providers(provider);
    }

    /** The Files API upload builder (ADR-060, CR-004). */
    public Upload upload() {
        return Upload.root(provider, apiKey, baseUrlOverride, http);
    }

    /**
     * Reports whether an explicit request for {@code capability} will not
     * hard-fail pre-flight on this client's provider (ADR-030). Gated
     * capabilities (caching, batching, file upload, image generation) dispatch
     * the same generated {@code *Config} lookups their strict validation paths
     * use — never a parallel table — so the query and the error cannot drift
     * (CAP-002). Capabilities with no provider-level pre-flight gate return
     * {@code true}. Says nothing about per-model or per-option rejections — use
     * the catalogue's {@code ModelInfo.capabilities} for model-level facts.
     * Synchronous, no IO.
     */
    public boolean supports(Capability capability) {
        return switch (capability) {
            case CACHING -> Caching.config(provider) != null;
            case BATCHING -> Batch.config(provider) != null;
            case FILE_UPLOAD -> Request.fileUploadConfig(provider) != null;
            case IMAGE_GENERATION -> ImageGenDef.imageGenConfig(provider) != null;
            default -> true;
        };
    }
}
