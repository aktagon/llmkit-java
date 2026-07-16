package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Batch;
import com.aktagon.llmkit.providers.generated.Caching;
import com.aktagon.llmkit.providers.generated.ImageGenDef;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Request;

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

    /** Create a client for a provider. */
    public Client(ProviderName provider, String apiKey) {
        this(provider, apiKey, new JdkHttpTransport());
    }

    /** Transport-injecting constructor (tests supply a capturing fake). */
    Client(ProviderName provider, String apiKey, HttpTransport http) {
        this(provider, apiKey, null, http);
    }

    private Client(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
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
        return new Client(provider, apiKey, url, http);
    }

    /** The text-generation builder. */
    public Text text() {
        return Text.root(provider, apiKey, baseUrlOverride, http);
    }

    /** A fresh stateful tool-loop agent (the one stateful builder). */
    public Agent agent() {
        return new Agent(provider, apiKey, baseUrlOverride, http);
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
