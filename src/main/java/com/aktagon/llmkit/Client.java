package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderName;

/**
 * The entry point to the SDK. Immutable; builders reached from it clone on
 * chain (ADR-068 JAVA-004: synchronous public API, mirroring the Go SDK).
 * Phase 0 exposes the {@link #text()} builder's non-streaming
 * {@code prompt} terminal for the OpenAI ChatCompletion slice.
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
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = null;
        this.http = http;
    }

    /** Convenience constructor for OpenAI. */
    public static Client openai(String apiKey) {
        return new Client(ProviderName.OPENAI, apiKey);
    }

    /** The text-generation builder. */
    public Text text() {
        return Text.root(provider, apiKey, baseUrlOverride, http);
    }
}
